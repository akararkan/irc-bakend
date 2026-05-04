package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes a serialised {@link PostRealtimeEvent} to the Redis pub/sub
 * channel {@code irc:posts:{postId}}.
 *
 * <p>Every running application instance subscribes to the pattern
 * {@code irc:posts:*} via {@link PostRealtimeSubscriber}, so an event
 * produced on one instance reaches the SSE subscribers of every other
 * instance.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:posts:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID postId, PostRealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + postId, json);
            log.debug("[POST-PUB] {} → post={}", event.getEventType(), postId);
        } catch (Exception ex) {
            log.error("[POST-PUB] Failed to publish post event post={}: {}", postId, ex.getMessage(), ex);
        }
    }
}
