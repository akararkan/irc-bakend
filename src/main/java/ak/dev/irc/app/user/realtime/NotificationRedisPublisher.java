package ak.dev.irc.app.user.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes a serialised {@link ak.dev.irc.app.user.dto.response.NotificationResponse}
 * to the Redis pub/sub channel {@code irc:notifications:{userId}}.
 *
 * <p>Every running application instance subscribes to the pattern
 * {@code irc:notifications:*} via a {@code NotificationRedisSubscriber}.
 * This means a notification produced by any instance will reach the SSE
 * connection held by any other instance — giving us horizontal-scale safety
 * with zero extra infrastructure.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisPublisher {

    public static final String CHANNEL_PREFIX = "irc:notifications:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID recipientId, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientId, json);
            log.debug("[SSE-PUB] Notification published to Redis channel for user={}", recipientId);
        } catch (Exception ex) {
            // Never let a Redis failure break the calling thread
            log.error("[SSE-PUB] Failed to publish notification to Redis for user={}: {}",
                    recipientId, ex.getMessage(), ex);
        }
    }
}

