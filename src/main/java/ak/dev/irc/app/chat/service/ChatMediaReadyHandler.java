package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.media.dto.MediaVariants;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.service.MediaIngestService;
import ak.dev.irc.app.media.service.MediaReadyDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When a chat video finishes its transcode ladder, rewrite the message's
 * {@link MediaRef} (url → best rendition, poster as thumbnail, real dims) in
 * both message tables and broadcast {@code MESSAGE_EDITED} so open clients
 * re-render — no new client protocol, refresh shows the same thing.
 *
 * <p>The ref is matched by its deterministic {@code media/{assetId}/…} storage
 * key. Failures are the dispatcher's to log; the message keeps serving the
 * original either way.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMediaReadyHandler implements MediaReadyDispatcher.MediaReadyHandler {

    public static final String SURFACE = "CHAT_MESSAGE";

    private final MessageByIdRepository messageByIdRepo;
    private final MessageByConversationRepository messageRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRealtimeBroadcaster broadcaster;

    @Override
    public String surface() {
        return SURFACE;
    }

    @Override
    public void onMediaReady(MediaAsset asset, List<MediaRendition> renditions, String entityKey) {
        long messageId = Long.parseLong(entityKey);
        MessageByIdEntity m = messageByIdRepo.findById(messageId).orElse(null);
        if (m == null || Boolean.TRUE.equals(m.getDeleted())
                || m.getMedia() == null || m.getMedia().isEmpty()) {
            return;
        }

        Map<String, String> variants = MediaVariants.toClientMap(renditions);
        String bestUrl = MediaVariants.bestVideoUrl(variants);
        String posterUrl = variants.get("poster");
        String bestKey = keyForUrl(renditions, bestUrl);
        String posterKey = keyForUrl(renditions, posterUrl);

        boolean changed = false;
        for (MediaRef ref : m.getMedia()) {
            UUID refAsset = MediaIngestService.assetIdFromKey(ref.getStorageKey());
            if (!asset.getId().equals(refAsset)) continue;
            if (bestUrl != null && !bestUrl.equals(ref.getUrl())) {
                ref.setUrl(bestUrl);
                if (bestKey != null) ref.setStorageKey(bestKey);
                changed = true;
            }
            if (posterUrl != null && ref.getThumbnailUrl() == null) {
                ref.setThumbnailUrl(posterUrl);
                ref.setThumbnailKey(posterKey);
                changed = true;
            }
            if (ref.getWidth() == null && asset.getWidth() != null) {
                ref.setWidth(asset.getWidth());
                ref.setHeight(asset.getHeight());
                changed = true;
            }
            if (ref.getDurationMs() == null && asset.getDurationMs() != null) {
                ref.setDurationMs(asset.getDurationMs());
                changed = true;
            }
        }
        if (!changed) return;

        messageByIdRepo.save(m);
        messageRepo.pointRead(m.getConversationId(), m.getBucket(), messageId).ifPresent(row -> {
            row.setMedia(m.getMedia());
            messageRepo.save(row);
        });

        broadcaster.broadcast(memberRepo.findReadableMemberIds(m.getConversationId()),
                ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.MESSAGE_EDITED)
                        .conversationId(m.getConversationId())
                        .messageId(messageId)
                        .body(m.getBody())
                        .editedAt(Instant.now())
                        .build());
        log.info("[CHAT-MEDIA] message {} upgraded to processed renditions of {}", messageId, asset.getId());
    }

    private static String keyForUrl(List<MediaRendition> renditions, String url) {
        if (url == null) return null;
        for (MediaRendition r : renditions) {
            if (url.equals(r.getUrl())) return r.getObjectKey();
        }
        return null;
    }
}
