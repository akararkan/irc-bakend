package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoryRealtimeSubscriber implements MessageListener {

    private final StoryRealtimeService realtimeService;
    private final ObjectMapper         objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel   = new String(message.getChannel());
        String storyIdStr = channel.replace(StoryRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID storyId = UUID.fromString(storyIdStr);
            StoryRealtimeEvent event = objectMapper.readValue(message.getBody(), StoryRealtimeEvent.class);
            realtimeService.broadcast(storyId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[STORY-SUB] Bad storyId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[STORY-SUB] Failed to process story event: {}", ex.getMessage(), ex);
        }
    }
}
