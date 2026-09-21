package ak.dev.irc.app.media.job;

import ak.dev.irc.app.admin.ops.JobPauseRegistry;
import ak.dev.irc.app.admin.ops.JobRunRecorder;
import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.RenditionLabels;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Storage-cost reclaim: once a video has an HLS master (and therefore a full
 * ladder), its stored {@code original} rendition — up to 512 MB of source —
 * stops earning its keep. After {@code media.video.purge-ladder-original-after-days}
 * days the object and its row are removed and {@code storedBytes} recomputed.
 *
 * <p><b>Disabled by default</b> ({@code -1}): the original is also the dedup
 * source and the only fallback when a ladder must be regenerated, so purging
 * is an explicit operator decision. The candidate query requires the HLS
 * rendition, so passthrough assets (transcode off) are never touched.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LadderOriginalPurgeJob {

    private static final String JOB = "media-ladder-original-purge";
    private static final int BATCH = 50;

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final S3StorageService storage;
    private final MediaProperties props;
    private final JobPauseRegistry jobPause;
    private final JobRunRecorder jobRuns;

    @Scheduled(initialDelayString = "${media.video.purge-initial-delay-ms:300000}",
               fixedDelayString  = "${media.video.purge-interval-ms:3600000}")
    public void purge() {
        int afterDays = props.getVideo().getPurgeLadderOriginalAfterDays();
        if (afterDays < 0 || jobPause.isPaused(JOB)) return;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(afterDays);
        List<MediaAsset> candidates = assetRepo.findLadderOriginalPurgeCandidates(
                MediaStatus.READY,
                EnumSet.of(MediaAssetType.VIDEO, MediaAssetType.FILM, MediaAssetType.VIDEO_CLIP),
                cutoff, PageRequest.of(0, BATCH));
        if (candidates.isEmpty()) return;

        var run = jobRuns.start(JOB, null);
        int purged = 0, skipped = 0, errors = 0;
        for (MediaAsset asset : candidates) {
            try {
                // Same dedup guard as MediaDeleteService: another asset row
                // sharing this content hash points at the SAME objects — a
                // purge here would dangle its `original` reference. Dedup
                // groups therefore keep their original for good; correctness
                // over the marginal bytes.
                if (asset.getContentHash() != null
                        && assetRepo.countByContentHashAndIdNot(asset.getContentHash(), asset.getId()) > 0) {
                    skipped++;
                    continue;
                }
                purgeOne(asset.getId());
                purged++;
            } catch (Exception ex) {
                errors++;
                log.warn("[MEDIA-PURGE] original purge for {} failed: {}", asset.getId(), ex.getMessage());
            }
        }
        jobRuns.finish(run, new JobRunRecorder.JobStats(purged, errors), null);
        log.info("[MEDIA-PURGE] ladder originals purged={} dedup-skipped={} errors={}", purged, skipped, errors);
    }

    /**
     * Object first, then row, then the storedBytes recount — a crash between
     * steps leaves either an orphaned object (reconcilable) or a stale count
     * (self-heals on the next write), never a dangling reference. Each repo
     * call commits on its own (no {@code @Transactional} here — self-invoked
     * proxied methods would silently skip it anyway); the ordering carries
     * the safety.
     */
    private void purgeOne(UUID assetId) {
        List<MediaRendition> renditions = renditionRepo.findByIdMediaId(assetId);
        MediaRendition original = renditions.stream()
                .filter(r -> RenditionLabels.ORIGINAL.equals(r.getId().getLabel()))
                .findFirst().orElse(null);
        if (original == null) return;   // raced with a delete — nothing to do

        storage.delete(original.getObjectKey());   // impl swallows storage failures
        renditionRepo.deleteById(original.getId());

        long remaining = renditions.stream()
                .filter(r -> !RenditionLabels.ORIGINAL.equals(r.getId().getLabel()))
                .mapToLong(r -> r.getBytes() == null ? 0 : r.getBytes()).sum();
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStoredBytes(remaining);
            assetRepo.save(a);
        });
    }
}
