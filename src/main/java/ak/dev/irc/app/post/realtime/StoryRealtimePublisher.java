package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoryRealtimePublisher {

    public static final String CHANNEL_PREFIX = "irc:stories:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void publish(UUID storyId, StoryRealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + storyId, json);
            log.debug("[STORY-PUB] {} → story={}", event.getEventType(), storyId);
        } catch (Exception ex) {
            log.error("[STORY-PUB] Failed to publish story event story={}: {}", storyId, ex.getMessage(), ex);
        }
    }
}
