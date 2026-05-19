package ak.dev.irc.app.post.cassandra.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes feed events on a per-USER Redis channel so the user's open SSE
 * connection on any instance can push the new post to the client instantly.
 *
 * Channel format:  irc:feed:{userId}
 *
 * Sibling of NotificationRedisPublisher / PostRealtimePublisher — separate
 * channel namespace keeps the SSE router simple.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:feed:";

    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    public void publishToUser(UUID viewerId, Map<String, Object> event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redis.convertAndSend(CHANNEL_PREFIX + viewerId, json);
        } catch (Exception e) {
            // Cache miss is acceptable — the post is in Cassandra; next /feed read returns it.
            log.debug("[FEED-REALTIME] publish to user {} skipped: {}", viewerId, e.getMessage());
        }
    }
}
