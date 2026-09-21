package ak.dev.irc.app.media.job;

import ak.dev.irc.app.admin.ops.JobPauseRegistry;
import ak.dev.irc.app.admin.ops.JobRunRecorder;
import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.event.MediaEventPublisher;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.service.MediaAssetStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Safety net for the media queue (mirrors {@code ModerationSlaSweeper}): the
 * database is the source of truth, the broker only the fast path. An asset
 * stuck PROCESSING — lost message, dead-lettered after retries, worker crash —
 * is republished until {@code media.processing.max-attempts}, then failed
 * terminally so nothing sits in limbo forever.
 *
 * <p>Staleness is judged on {@code updatedAt}, which every per-rung write
 * refreshes — a long-but-alive transcode is never mistaken for a stuck one.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaStuckSweeper {

    private static final String JOB = "media-stuck-sweep";
    private static final int BATCH = 25;

    private final MediaAssetRepository assetRepo;
    private final MediaAssetStatusService statusService;
    private final MediaEventPublisher publisher;
    private final MediaProperties props;
    private final JobPauseRegistry jobPause;
    private final JobRunRecorder jobRuns;

    @Scheduled(initialDelayString = "${media.processing.sweeper-initial-delay-ms:30000}",
               fixedDelayString  = "${media.processing.sweeper-interval-ms:60000}")
    public void sweep() {
        if (jobPause.isPaused(JOB)) return;

        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(props.getProcessing().getStuckAfterSeconds());
        List<MediaAsset> stuck = assetRepo.findByStatusAndUpdatedAtBefore(
                MediaStatus.PROCESSING, cutoff, PageRequest.of(0, BATCH));
        if (stuck.isEmpty()) return;   // quiet ticks leave no ledger rows

        var run = jobRuns.start(JOB, null);
        int requeued = 0, failed = 0, errors = 0;
        int maxAttempts = props.getProcessing().getMaxAttempts();
        for (MediaAsset asset : stuck) {
            try {
                if (MediaAssetStatusService.attemptsOf(asset) >= maxAttempts) {
                    statusService.markFailed(asset.getId(), MediaStatus.FAILED_PROCESSING,
                            "Exceeded " + maxAttempts + " processing attempts.");
                    failed++;
                } else {
                    // The attempt bump also refreshes updatedAt, giving the
                    // republished run a fresh staleness window.
                    statusService.recordAttemptFailure(asset.getId(),
                            "Republished by stuck sweeper.");
                    publisher.publishProcessRequested(asset.getId(), "sweeper-retry");
                    requeued++;
                }
            } catch (Exception ex) {
                errors++;
                log.warn("[MEDIA-SWEEP] asset {} sweep failed: {}", asset.getId(), ex.getMessage());
            }
        }
        jobRuns.finish(run, new JobRunRecorder.JobStats(requeued + failed, errors), null);
        log.info("[MEDIA-SWEEP] requeued={} terminally-failed={} errors={}", requeued, failed, errors);
    }
}
