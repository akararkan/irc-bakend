package ak.dev.irc.app.qna.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes a serialised {@link QnaRealtimeEvent} to the Redis pub/sub channel
 * {@code irc:questions:{questionId}}.
 *
 * <p>Every running application instance subscribes to {@code irc:questions:*}
 * via {@link QnaRealtimeSubscriber}, so an event produced on one instance
 * reaches the SSE subscribers of every other instance.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnaRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:questions:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID questionId, QnaRealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + questionId, json);
            log.debug("[QNA-PUB] {} → question={}", event.getEventType(), questionId);
        } catch (Exception ex) {
            log.error("[QNA-PUB] Failed to publish question event question={}: {}",
                    questionId, ex.getMessage(), ex);
        }
    }
}
