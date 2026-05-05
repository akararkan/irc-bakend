package ak.dev.irc.app.research.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Per-research Redis pub/sub channel: {@code irc:research:{researchId}}.
 * Mirrors the post and Q&A patterns so all three corpora share the same
 * cluster-wide realtime fabric.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:research:";

    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    public void publish(UUID researchId, ResearchRealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redis.convertAndSend(CHANNEL_PREFIX + researchId, json);
            log.debug("[RESEARCH-PUB] {} → research={}", event.getEventType(), researchId);
        } catch (Exception ex) {
            log.error("[RESEARCH-PUB] Failed to publish: {}", ex.getMessage(), ex);
        }
    }
}
