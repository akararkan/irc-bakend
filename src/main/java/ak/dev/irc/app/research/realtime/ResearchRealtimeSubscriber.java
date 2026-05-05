package ak.dev.irc.app.research.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Forwards research events from Redis pub/sub to the local SSE manager so
 * every running app instance can serve any connected viewer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchRealtimeSubscriber implements MessageListener {

    private final ResearchRealtimeService realtimeService;
    private final ObjectMapper           objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String idStr   = channel.replace(ResearchRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID researchId = UUID.fromString(idStr);
            ResearchRealtimeEvent event = objectMapper.readValue(message.getBody(), ResearchRealtimeEvent.class);
            realtimeService.broadcast(researchId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[RESEARCH-SUB] bad researchId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[RESEARCH-SUB] failed to process: {}", ex.getMessage(), ex);
        }
    }
}
