package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes a {@link StoryTrayEvent} to {@code irc:story-tray:{viewerId}}.
 *
 * One event per recipient — fan-out is done by the caller (StoryServiceImpl)
 * which iterates over follower/close-friend IDs and calls this once per viewer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryTrayRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:story-tray:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID viewerId, StoryTrayEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + viewerId, json);
            log.debug("[TRAY-PUB] {} → viewer={} author={}",
                event.getEventType(), viewerId, event.getAuthorId());
        } catch (Exception ex) {
            log.error("[TRAY-PUB] Failed to publish tray event viewer={}: {}",
                viewerId, ex.getMessage(), ex);
        }
    }
}
