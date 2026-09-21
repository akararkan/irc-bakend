package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Full asset removal — objects then rows — shared by the async delete worker
 * and the owner-facing DELETE endpoint.
 *
 * <p>Dedup safety (media-storage.md leak L3, previously a live bug): reference
 * rows ({@code storedBytes == 0}) and assets whose content hash another asset
 * still uses keep their <b>objects</b> — only the rows die. An orphaned object
 * is cheap and reconcilable; a dangling reference is a correctness bug.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaDeleteService {

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final MediaAssetStatusService statusService;
    private final S3StorageService storage;

    /** Delete an asset's objects (when safe) and all its rows. Idempotent. */
    public void deleteAssetNow(UUID assetId) {
        MediaAsset asset = assetRepo.findById(assetId).orElse(null);
        if (asset == null) return;

        boolean sharesObjects = isReferenceRow(asset) || hasDedupSiblings(asset);
        List<MediaRendition> renditions = renditionRepo.findByIdMediaId(assetId);

        if (!sharesObjects) {
            for (MediaRendition r : renditions) {
                storage.delete(r.getObjectKey());   // impl swallows storage failures
            }
            storage.delete("raw/" + assetId);
            // Prefix sweep catches objects a crashed run left behind without rows.
            try {
                for (S3StorageService.StoredObject obj : storage.list("media/" + assetId + "/", 1000)) {
                    storage.delete(obj.key());
                }
            } catch (UnsupportedOperationException ignored) {
                // No-op storage backend — nothing listed, nothing to sweep.
            } catch (Exception listEx) {
                log.warn("[MEDIA-DELETE] prefix sweep for {} failed: {}", assetId, listEx.getMessage());
            }
        }
        statusService.deleteAssetRows(assetId);
        log.info("[MEDIA-DELETE] asset {} removed (objectsDeleted={})", assetId, !sharesObjects);
    }

    /** Delete one pre-pipeline object key verbatim. */
    public void deleteLegacyKey(String key) {
        if (key == null || key.isBlank()) return;
        storage.delete(key);
    }

    private static boolean isReferenceRow(MediaAsset asset) {
        return asset.getStoredBytes() != null && asset.getStoredBytes() == 0L
                && asset.getContentHash() != null;
    }

    private boolean hasDedupSiblings(MediaAsset asset) {
        return asset.getContentHash() != null
                && assetRepo.countByContentHashAndIdNot(asset.getContentHash(), asset.getId()) > 0;
    }
}
