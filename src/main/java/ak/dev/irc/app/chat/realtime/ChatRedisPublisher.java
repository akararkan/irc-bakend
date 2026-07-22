package ak.dev.irc.app.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes a chat event to the Redis channel {@code irc:chat:{recipientId}}.
 * Every running instance subscribes to {@code irc:chat:*} via
 * {@link ChatRedisSubscriber}, so an event produced on any instance reaches the
 * SSE connection held on whichever instance the recipient is connected to.
 *
 * <p>Each message carries an {@code {event, data}} envelope so the receiver picks
 * the SSE event name without sniffing the payload — identical to the notification
 * channel design.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRedisPublisher {

    public static final String CHANNEL_PREFIX = "irc:chat:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(UUID recipientId, ChatRealtimeEvent event) {
        if (recipientId == null || event == null || event.getEventType() == null) return;
        try {
            Map<String, Object> envelope = Map.of(
                    "event", event.getEventType().wire(),
                    "data", event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientId,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            // Never let a Redis failure break the calling thread.
            log.error("[CHAT-PUB] failed to publish {} for user={}: {}",
                    event.getEventType(), recipientId, ex.getMessage());
        }
    }
}
