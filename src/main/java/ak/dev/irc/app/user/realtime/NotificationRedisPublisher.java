package ak.dev.irc.app.user.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes notification SSE messages to the Redis pub/sub channel
 * {@code irc:notifications:{userId}}.
 *
 * <p>Every running application instance subscribes to {@code irc:notifications:*}
 * via {@link NotificationRedisSubscriber}, so a notification produced by any
 * instance reaches the SSE connection held by any other instance.</p>
 *
 * <p>Each message carries an envelope of {@code {event, data}} so the receiver
 * can pick the correct SSE event name (notification / unread-count / read /
 * deleted) without sniffing the payload shape.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisPublisher {

    public static final String CHANNEL_PREFIX = "irc:notifications:";

    /** SSE event names — clients add listeners for each. */
    public enum SseEventName {
        NOTIFICATION("notification"),
        UNREAD_COUNT("unread-count"),
        READ("read"),
        DELETED("deleted");

        private final String wire;
        SseEventName(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID recipientId, SseEventName eventName, Object payload) {
        try {
            Map<String, Object> envelope = Map.of(
                    "event", eventName.wire(),
                    "data", payload
            );
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientId, json);
            log.debug("[SSE-PUB] {} published for user={}", eventName.wire(), recipientId);
        } catch (Exception ex) {
            // Never let a Redis failure break the calling thread
            log.error("[SSE-PUB] Failed to publish {} for user={}: {}",
                    eventName, recipientId, ex.getMessage(), ex);
        }
    }
}
