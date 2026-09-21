package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaAttachment;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaAttachmentRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Push side of "renditions are ready": surfaces that need a write-back when
 * the ladder lands (chat rewrites the message's MediaRef and broadcasts)
 * register a {@link MediaReadyHandler} and {@code track(...)} each processing
 * asset; the worker calls {@link #dispatch} after READY. Surfaces that hydrate
 * variants at read time (posts, stories) register nothing.
 *
 * <p>Dispatch failures are isolated per attachment and never propagate — the
 * asset is READY regardless; a missed push just means the client sees the
 * upgrade on its next read.</p>
 */
@Slf4j
@Service
public class MediaReadyDispatcher {

    /** One per surface that wants a ready push. Key = {@link #surface()}. */
    public interface MediaReadyHandler {
        String surface();
        void onMediaReady(MediaAsset asset, List<MediaRendition> renditions, String entityKey);
    }

    private final MediaAttachmentRepository attachmentRepo;
    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final Map<String, MediaReadyHandler> handlers = new HashMap<>();

    public MediaReadyDispatcher(MediaAttachmentRepository attachmentRepo,
                                MediaAssetRepository assetRepo,
                                MediaRenditionRepository renditionRepo,
                                List<MediaReadyHandler> registered) {
        this.attachmentRepo = attachmentRepo;
        this.assetRepo = assetRepo;
        this.renditionRepo = renditionRepo;
        for (MediaReadyHandler h : registered) {
            handlers.put(h.surface(), h);
        }
    }

    /** Remember that {@code entityKey} on {@code surface} embeds this asset. */
    @Transactional
    public void track(UUID assetId, String surface, String entityKey) {
        if (assetId == null || handlers.isEmpty() || !handlers.containsKey(surface)) return;
        try {
            attachmentRepo.save(MediaAttachment.builder()
                    .id(new MediaAttachment.MediaAttachmentId(assetId, entityKey))
                    .surface(surface)
                    .build());
        } catch (Exception ex) {
            log.warn("[MEDIA-READY] track failed for {} on {}: {}", assetId, surface, ex.getMessage());
        }
    }

    /** Called by the worker after an asset flips READY. Never throws. */
    public void dispatch(UUID assetId) {
        try {
            List<MediaAttachment> attachments = attachmentRepo.findByIdAssetId(assetId);
            if (attachments.isEmpty()) return;
            MediaAsset asset = assetRepo.findById(assetId).orElse(null);
            if (asset == null) return;
            List<MediaRendition> renditions = renditionRepo.findByIdMediaId(assetId);
            for (MediaAttachment att : attachments) {
                MediaReadyHandler handler = handlers.get(att.getSurface());
                if (handler == null) continue;
                try {
                    handler.onMediaReady(asset, renditions, att.getId().getEntityKey());
                } catch (Exception ex) {
                    log.warn("[MEDIA-READY] handler {} failed for {} / {}: {}",
                            att.getSurface(), assetId, att.getId().getEntityKey(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("[MEDIA-READY] dispatch failed for {}: {}", assetId, ex.getMessage());
        }
    }
}
