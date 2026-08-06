package ak.dev.irc.app.media.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.dto.MediaDtos.*;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the upload lifecycle (spec §20.4): upload-intent (validate + dedup
 * + presigned PUT), complete (download the raw upload + enqueue processing), read
 * status, and delete. The heavy transcode runs off-thread in
 * {@link MediaProcessingService}.
 *
 * <p>Raw uploads land at a deterministic {@code raw/{assetId}} key (7-day
 * retention, §20.6); renditions are produced under {@code media/{assetId}/…}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private static final int PRESIGN_MINUTES = 30;

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final MediaProcessingService processingService;
    private final S3StorageService storage;
    private final MediaProperties props;
    private final MediaQuotaService quotaService;

    // ── Upload intent ────────────────────────────────────────────────────────

    @Transactional
    public UploadIntentResponse uploadIntent(UUID ownerId, UploadIntentRequest req, MediaTier tier) {
        MediaAssetType type = MediaAssetType.parse(req.type(), MediaAssetType.IMAGE);

        // Validate declared size against the type cap (§20.2). Magic-byte / ffprobe
        // validation happens in the worker before any decode (§20.8).
        long cap = type.isVideo() ? props.getLimits().getVideoMaxBytes()
                                  : props.getLimits().getImageMaxBytes();
        if (req.sizeBytes() > cap) {
            throw new BadRequestException(
                    "File exceeds the maximum size for " + type + " (" + cap + " bytes).",
                    "MEDIA_TOO_LARGE");
        }

        // Per-role daily quota (§8) — checked before dedup so a capped user
        // can't keep minting reference rows either.
        quotaService.enforce(ownerId, req.sizeBytes());

        // Dedup (§20.5): a duplicate upload reuses existing renditions and only
        // creates a new reference row — no re-encode, no extra storage.
        if (req.sha256() != null && !req.sha256().isBlank()) {
            var existing = assetRepo.findFirstByContentHashAndStatus(req.sha256(), MediaStatus.READY);
            if (existing.isPresent()) {
                MediaAsset ref = dedupReference(ownerId, existing.get(), tier);
                return new UploadIntentResponse(ref.getId(), null, true, ref.getStatus().name());
            }
        }

        MediaAsset asset = assetRepo.save(MediaAsset.builder()
                .ownerId(ownerId)
                .type(type)
                .status(MediaStatus.PENDING)
                .contentHash(req.sha256())
                .originalBytes(req.sizeBytes())
                .requestedTier(tier == null ? MediaTier.HIGH : tier)
                .mime(req.mime())
                .build());

        String rawKey = rawKey(asset.getId());
        String presignedPutUrl;
        try {
            presignedPutUrl = storage.presignPut(rawKey, req.mime(), PRESIGN_MINUTES);
        } catch (UnsupportedOperationException ex) {
            // No-op storage (unconfigured) — surface a clear error.
            throw new BadRequestException(
                    "Media storage is not configured on this server.", "STORAGE_UNAVAILABLE");
        }
        return new UploadIntentResponse(asset.getId(), presignedPutUrl, false, asset.getStatus().name());
    }

    /** Create a new asset row that references an already-processed one's renditions. */
    private MediaAsset dedupReference(UUID ownerId, MediaAsset src, MediaTier tier) {
        MediaAsset ref = assetRepo.save(MediaAsset.builder()
                .ownerId(ownerId)
                .type(src.getType())
                .status(MediaStatus.READY)
                .contentHash(src.getContentHash())
                .originalBytes(src.getOriginalBytes())
                .storedBytes(0L)                    // shared bytes — not double-counted for the referrer
                .width(src.getWidth())
                .height(src.getHeight())
                .durationMs(src.getDurationMs())
                .blurhash(src.getBlurhash())
                .requestedTier(tier == null ? src.getRequestedTier() : tier)
                .mime(src.getMime())
                .build());
        List<MediaRendition> copies = new ArrayList<>();
        for (MediaRendition r : renditionRepo.findByIdMediaId(src.getId())) {
            copies.add(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(ref.getId(), r.getId().getLabel()))
                    .objectKey(r.getObjectKey())
                    .url(r.getUrl())
                    .bytes(r.getBytes())
                    .width(r.getWidth())
                    .height(r.getHeight())
                    .mime(r.getMime())
                    .build());
        }
        renditionRepo.saveAll(copies);
        return ref;
    }

    // ── Complete → process ───────────────────────────────────────────────────

    @Transactional
    public void complete(UUID ownerId, UUID assetId) {
        MediaAsset asset = owned(ownerId, assetId);
        if (asset.getStatus() != MediaStatus.PENDING) {
            return; // idempotent — already uploading/processing/ready
        }
        asset.setStatus(MediaStatus.PROCESSING);
        assetRepo.save(asset);

        byte[] bytes;
        try {
            var obj = storage.getObject(rawKey(assetId));
            bytes = obj.inputStream().readAllBytes();
        } catch (Exception ex) {
            asset.setStatus(MediaStatus.FAILED_VALIDATION);
            asset.setErrorMessage("Raw upload not found or unreadable.");
            assetRepo.save(asset);
            throw new BadRequestException("Uploaded file was not found. Re-upload and retry.",
                    "MEDIA_RAW_MISSING");
        }
        processingService.submit(assetId, bytes, asset.getType(), asset.getRequestedTier());
    }

    // ── Read status ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MediaStatusResponse get(UUID ownerId, UUID assetId) {
        MediaAsset asset = owned(ownerId, assetId);
        return MediaStatusResponse.of(asset, renditionRepo.findByIdMediaId(assetId));
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID ownerId, UUID assetId) {
        MediaAsset asset = owned(ownerId, assetId);
        // Delete renditions from storage first, then the rows, then the raw original
        // (§15: remove objects then the row, so a crash orphans an object — cheap,
        // nightly-reconciled — rather than a broken reference).
        for (MediaRendition r : renditionRepo.findByIdMediaId(assetId)) {
            try { storage.delete(r.getObjectKey()); }
            catch (Exception ex) { log.warn("[MEDIA] rendition delete failed {}: {}", r.getObjectKey(), ex.getMessage()); }
        }
        renditionRepo.deleteByIdMediaId(assetId);
        try { storage.delete(rawKey(assetId)); } catch (Exception ignored) { }
        assetRepo.delete(asset);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private MediaAsset owned(UUID ownerId, UUID assetId) {
        MediaAsset asset = assetRepo.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Media", "id", assetId));
        if (!asset.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this media.", "NOT_MEDIA_OWNER");
        }
        return asset;
    }

    private String rawKey(UUID assetId) {
        return "raw/" + assetId;
    }
}
