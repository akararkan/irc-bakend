package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Receives {@link StoryTrayEvent} from {@code irc:story-tray:*} and forwards
 * to the local {@link StoryTrayRealtimeService} to push to any open SSE
 * emitters for that viewer on this instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryTrayRealtimeSubscriber implements MessageListener {

    private final StoryTrayRealtimeService trayService;
    private final ObjectMapper             objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel    = new String(message.getChannel());
        String viewerStr  = channel.replace(StoryTrayRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID viewerId = UUID.fromString(viewerStr);
            StoryTrayEvent event = objectMapper.readValue(message.getBody(), StoryTrayEvent.class);
            trayService.deliver(viewerId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[TRAY-SUB] Bad viewerId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[TRAY-SUB] Failed to process tray event: {}", ex.getMessage(), ex);
        }
    }
}
