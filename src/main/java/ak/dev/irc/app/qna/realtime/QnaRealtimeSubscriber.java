package ak.dev.irc.app.qna.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Receives a {@link QnaRealtimeEvent} from {@code irc:questions:*} and forwards
 * it to the local {@link QnaRealtimeService}, which fans out to all SSE
 * subscribers on this instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnaRealtimeSubscriber implements MessageListener {

    private final QnaRealtimeService realtimeService;
    private final ObjectMapper       objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel       = new String(message.getChannel());
        String questionIdStr = channel.replace(QnaRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID questionId = UUID.fromString(questionIdStr);
            QnaRealtimeEvent event = objectMapper.readValue(message.getBody(), QnaRealtimeEvent.class);
            realtimeService.broadcast(questionId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[QNA-SUB] Bad questionId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[QNA-SUB] Failed to process question event message: {}", ex.getMessage(), ex);
        }
    }
}
