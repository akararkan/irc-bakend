package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * All transactional status writes for {@code media_assets} / {@code
 * media_renditions}, in a dedicated bean so every call crosses the Spring
 * proxy — the predecessor's {@code @Transactional protected} methods were
 * self-invoked and silently non-transactional.
 *
 * <p>Writes here also refresh {@code updatedAt}, which is the staleness clock
 * {@code MediaStuckSweeper} watches: per-rung progress keeps a long transcode
 * from being mistaken for a stuck one.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetStatusService {

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final ak.dev.irc.app.media.repository.MediaAttachmentRepository attachmentRepo;

    /** Null-safe attempts read — rows predating the column hold NULL. */
    public static int attemptsOf(MediaAsset a) {
        return a.getProcessingAttempts() == null ? 0 : a.getProcessingAttempts();
    }

    /** Create an asset row (any status) — the id is needed before object keys exist. */
    @Transactional
    public MediaAsset createAsset(UUID ownerId, MediaAssetType type, MediaStatus status,
                                  String contentHash, Long originalBytes, String mime,
                                  MediaTier tier) {
        return assetRepo.save(MediaAsset.builder()
                .ownerId(ownerId)
                .type(type)
                .status(status)
                .contentHash(contentHash)
                .originalBytes(originalBytes)
                .mime(mime)
                .requestedTier(tier == null ? MediaTier.HIGH : tier)
                .build());
    }

    @Transactional
    public void markProcessing(UUID assetId) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(MediaStatus.PROCESSING);
            assetRepo.save(a);
        });
    }

    /** Persist one produced rendition and touch the asset's staleness clock. */
    @Transactional
    public void saveRendition(MediaRendition rendition) {
        renditionRepo.save(rendition);
        // Explicit touch: re-saving an unchanged managed entity is a no-op for
        // Hibernate (no dirty fields → no UPDATE → @PreUpdate never fires), so
        // the staleness clock the sweeper watches must be set by hand here.
        assetRepo.findById(rendition.getId().getMediaId()).ifPresent(a -> {
            a.setUpdatedAt(LocalDateTime.now());
            assetRepo.save(a);
        });
    }

    /** Whether this (assetId, label) rendition already exists — retry-resume check. */
    @Transactional(readOnly = true)
    public boolean renditionExists(UUID assetId, String label) {
        return renditionRepo.existsById(new MediaRendition.MediaRenditionId(assetId, label));
    }

    /**
     * Terminal success. {@code purgeOriginalAt} is set only when a {@code raw/}
     * original exists to purge (the intent flow); interception-path assets serve
     * their original as a rendition and pass {@code null}.
     */
    @Transactional
    public void markReady(UUID assetId, long storedBytes, Integer width, Integer height,
                          Integer durationMs, LocalDateTime purgeOriginalAt) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(MediaStatus.READY);
            a.setStoredBytes(storedBytes);
            if (width != null) a.setWidth(width);
            if (height != null) a.setHeight(height);
            if (durationMs != null) a.setDurationMs(durationMs);
            a.setPurgeOriginalAt(purgeOriginalAt);
            a.setErrorMessage(null);
            assetRepo.save(a);
        });
    }

    /** Single-transaction finalize for the sync image path: renditions + READY. */
    @Transactional
    public void finalizeReadyWithRenditions(UUID assetId, List<MediaRendition> renditions,
                                            long storedBytes, Integer width, Integer height) {
        finalizeReadyWithRenditions(assetId, renditions, storedBytes, width, height, null);
    }

    /** Same finalize, carrying the placeholder blurhash when one was computed. */
    @Transactional
    public void finalizeReadyWithRenditions(UUID assetId, List<MediaRendition> renditions,
                                            long storedBytes, Integer width, Integer height,
                                            String blurhash) {
        renditionRepo.saveAll(renditions);
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(MediaStatus.READY);
            a.setStoredBytes(storedBytes);
            if (width != null) a.setWidth(width);
            if (height != null) a.setHeight(height);
            if (blurhash != null && a.getBlurhash() == null) a.setBlurhash(blurhash);
            assetRepo.save(a);
        });
    }

    /** Persist a computed placeholder blurhash (worker poster path); never overwrites. */
    @Transactional
    public void setBlurhash(UUID assetId, String blurhash) {
        if (blurhash == null || blurhash.isBlank()) return;
        assetRepo.findById(assetId).ifPresent(a -> {
            if (a.getBlurhash() == null) {
                a.setBlurhash(blurhash);
                assetRepo.save(a);
            }
        });
    }

    /** Schedule the {@code raw/} original for purge (intent-flow assets only). */
    @Transactional
    public void setRawRetention(UUID assetId, LocalDateTime purgeAt) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setPurgeOriginalAt(purgeAt);
            assetRepo.save(a);
        });
    }

    /** Terminal failure (validation/moderation/processing). */
    @Transactional
    public void markFailed(UUID assetId, MediaStatus status, String message) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(status);
            a.setErrorMessage(truncate(message));
            assetRepo.save(a);
        });
    }

    /** Transient failure: consume an attempt, keep PROCESSING (retry/sweeper decide). */
    @Transactional
    public void recordAttemptFailure(UUID assetId, String message) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setProcessingAttempts(attemptsOf(a) + 1);
            a.setErrorMessage(truncate(message));
            assetRepo.save(a);
        });
    }

    /** Row cleanup for a full asset delete (objects are the caller's concern). */
    @Transactional
    public void deleteAssetRows(UUID assetId) {
        renditionRepo.deleteByIdMediaId(assetId);
        attachmentRepo.deleteByIdAssetId(assetId);
        assetRepo.deleteById(assetId);
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
