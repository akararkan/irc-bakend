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
 * Receives a {@link PostRealtimeEvent} from {@code irc:posts:*} and forwards
 * it to the local {@link PostRealtimeService}, which fans out to all SSE
 * subscribers on this instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostRealtimeSubscriber implements MessageListener {

    private final PostRealtimeService realtimeService;
    private final ObjectMapper        objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel   = new String(message.getChannel());
        String postIdStr = channel.replace(PostRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID postId = UUID.fromString(postIdStr);
            PostRealtimeEvent event = objectMapper.readValue(message.getBody(), PostRealtimeEvent.class);
            realtimeService.broadcast(postId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[POST-SUB] Bad postId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[POST-SUB] Failed to process post event message: {}", ex.getMessage(), ex);
        }
    }
}
