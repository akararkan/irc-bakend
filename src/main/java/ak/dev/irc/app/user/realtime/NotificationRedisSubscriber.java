package ak.dev.irc.app.user.realtime;

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
 * Subscribes to the Redis pattern {@code irc:notifications:*}.
 *
 * <p>Messages now arrive as a {@code {event, data}} envelope so the subscriber
 * can route to the correct SSE event name (notification / unread-count /
 * read / deleted). Any unrecognised event name is forwarded as raw JSON to
 * preserve forward-compatibility with new event types.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisSubscriber implements MessageListener {

    private final NotificationSseService sseService;
    private final ObjectMapper           objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel   = new String(message.getChannel());
        String userIdStr = channel.replace(NotificationRedisPublisher.CHANNEL_PREFIX, "");

        try {
            UUID userId = UUID.fromString(userIdStr);
            JsonNode root = objectMapper.readTree(message.getBody());
            String eventName = root.path("event").asText("notification");
            Object data = objectMapper.treeToValue(root.path("data"), Object.class);

            sseService.push(userId, eventName, data);
            log.debug("[SSE-SUB] Forwarded {} to SSE for user={}", eventName, userId);

        } catch (IllegalArgumentException ex) {
            log.warn("[SSE-SUB] Could not parse userId from channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[SSE-SUB] Failed to process Redis notification message: {}", ex.getMessage(), ex);
        }
    }
}
