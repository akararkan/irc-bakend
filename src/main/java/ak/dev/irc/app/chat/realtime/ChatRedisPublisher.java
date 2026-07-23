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
            redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientId, serialize(event));
        } catch (Exception ex) {
            // Never let a Redis failure break the calling thread.
            log.error("[CHAT-PUB] failed to publish {} for user={}: {}",
                    event.getEventType(), recipientId, ex.getMessage());
        }
    }

    /**
     * Fan one event out to many recipients: the envelope is identical for every
     * recipient (the user id lives only in the channel name), so serialize it
     * once and push all PUBLISH commands in a single pipelined round-trip —
     * instead of N serializations + N round-trips for an N-member conversation.
     */
    public void publishAll(java.util.Collection<UUID> recipientIds, ChatRealtimeEvent event) {
        if (recipientIds == null || recipientIds.isEmpty()
                || event == null || event.getEventType() == null) return;
        try {
            String payload = serialize(event);
            if (recipientIds.size() == 1) {
                redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientIds.iterator().next(), payload);
                return;
            }
            byte[] raw = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
                for (UUID recipientId : recipientIds) {
                    conn.publish((CHANNEL_PREFIX + recipientId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), raw);
                }
                return null;
            });
        } catch (Exception ex) {
            log.error("[CHAT-PUB] failed to fan out {} to {} recipients: {}",
                    event.getEventType(), recipientIds.size(), ex.getMessage());
        }
    }

    private String serialize(ChatRealtimeEvent event) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "event", event.getEventType().wire(),
                "data", event));
    }
}
