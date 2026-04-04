package ak.dev.irc.app.user.realtime;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Subscribes to the Redis pattern {@code irc:notifications:*}.
 *
 * <p>When a message arrives it deserialises the JSON body into a
 * {@link NotificationResponse} and forwards it to the local
 * {@link NotificationSseService}, which pushes the event to the
 * connected SSE emitter for that user (if any is open on this instance).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisSubscriber implements MessageListener {

    private final NotificationSseService sseService;
    private final ObjectMapper           objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel    = new String(message.getChannel());
        String userIdStr  = channel.replace(NotificationRedisPublisher.CHANNEL_PREFIX, "");

        try {
            UUID userId = UUID.fromString(userIdStr);
            NotificationResponse response = objectMapper.readValue(
                    message.getBody(), NotificationResponse.class);

            sseService.push(userId, response);
            log.debug("[SSE-SUB] Forwarded notification to SSE for user={}", userId);

        } catch (IllegalArgumentException ex) {
            log.warn("[SSE-SUB] Could not parse userId from channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[SSE-SUB] Failed to process Redis notification message: {}", ex.getMessage(), ex);
        }
    }
}

