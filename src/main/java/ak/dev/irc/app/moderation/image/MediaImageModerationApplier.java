package ak.dev.irc.app.moderation.image;

import ak.dev.irc.app.media.event.MediaEventPublisher;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Applies an admin's verdict on a {@link ModeratedEntityType#MEDIA_IMAGE} case
 * from the review queue (docs/moderation/image-moderation.md).
 *
 * <p>Review-band images publish immediately (availability first for the
 * uncertain middle), so approve is a no-op — the asset is already visible.
 * Reject deletes the whole media asset (every rendition + rows) through the
 * async delete queue, which is idempotent: re-driving a reject for an
 * already-deleted asset is a harmless no-op, exactly what the sweeper's
 * re-apply contract requires.</p>
 *
 * <p>Deliberately only the asset is touched, not the post/story/message that
 * embeds it — the embedding entity keeps its text and its other media, and its
 * stored URL simply goes dead. Taking down the whole post over one flagged
 * image is an editorial decision that belongs to a human, via the existing
 * content-moderation admin tools.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaImageModerationApplier implements ModerationApplier {

    private final MediaEventPublisher eventPublisher;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.MEDIA_IMAGE;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        // Already public — nothing to flip.
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        UUID assetId = parseAssetId(moderationCase.getEntityRef());
        if (assetId == null) {
            log.warn("[MODERATION-IMG] reject for case {} has non-UUID entityRef '{}' — nothing to delete",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return;
        }
        eventPublisher.publishDeleteAsset(assetId);
        log.info("[MODERATION-IMG] rejected image asset {} queued for deletion (case {})",
                assetId, moderationCase.getId());
    }

    private static UUID parseAssetId(String entityRef) {
        try {
            return UUID.fromString(entityRef);
        } catch (Exception ex) {
            return null;
        }
    }
}
