package ak.dev.irc.app.activity.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes a serialised {@link UserActivityRealtimeEvent} to the Redis
 * pub/sub channel {@code irc:activity:{userId}}. Every running instance
 * subscribes via {@link UserActivityRealtimeSubscriber} so the event reaches
 * every tab the user has open, regardless of which instance they hit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:activity:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void publish(UUID userId, UserActivityRealtimeEvent event) {
        if (userId == null || event == null) return;
        try {
            String json = objectMapper.writeValueAsString(event);
            redis.convertAndSend(CHANNEL_PREFIX + userId, json);
        } catch (Exception ex) {
            log.debug("[ACTIVITY-PUB] failed user={}: {}", userId, ex.getMessage());
        }
    }
}
