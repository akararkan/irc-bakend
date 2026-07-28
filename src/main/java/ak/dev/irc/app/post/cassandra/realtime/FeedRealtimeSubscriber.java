package ak.dev.irc.app.post.cassandra.realtime;

import ak.dev.irc.app.user.realtime.NotificationSseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Subscribes to the Redis pattern {@code irc:feed:*} and bridges each
 * per-user feed event onto the browser's existing notification SSE stream
 * ({@link NotificationSseService}) so a home-feed prepend can be wired
 * client-side.
 *
 * <p>Without this bridge {@link FeedRealtimePublisher} published to a channel
 * that nothing subscribed to, so {@code FEED_NEW_POST} never reached the
 * browser. Feed payloads are bare {@code {event, postId, authorId}} (not the
 * {@code {event, data}} envelope), so the whole payload is forwarded as the
 * SSE {@code data} under the event name carried in the {@code event} field.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRealtimeSubscriber implements MessageListener {

    private final NotificationSseService sseService;
    private final ObjectMapper           objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel   = new String(message.getChannel());
        String userIdStr = channel.replace(FeedRealtimePublisher.CHANNEL_PREFIX, "");

        try {
            UUID userId = UUID.fromString(userIdStr);
            JsonNode root = objectMapper.readTree(message.getBody());
            String eventName = root.path("event").asText("FEED_NEW_POST");
            Object data = objectMapper.treeToValue(root, Object.class);

            sseService.push(userId, eventName, data);
            log.debug("[FEED-SUB] Forwarded {} to SSE for user={}", eventName, userId);

        } catch (IllegalArgumentException ex) {
            log.warn("[FEED-SUB] Could not parse userId from channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[FEED-SUB] Failed to process Redis feed message: {}", ex.getMessage(), ex);
        }
    }
}
