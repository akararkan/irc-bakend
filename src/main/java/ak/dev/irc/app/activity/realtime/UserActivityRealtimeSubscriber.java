package ak.dev.irc.app.activity.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Receives a {@link UserActivityRealtimeEvent} from {@code irc:activity:*}
 * and forwards it to the local {@link UserActivityRealtimeService}, which
 * fans out to all SSE subscribers connected to this instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityRealtimeSubscriber implements MessageListener {

    private final UserActivityRealtimeService realtimeService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel  = new String(message.getChannel());
        String userIdStr = channel.replace(UserActivityRealtimePublisher.CHANNEL_PREFIX, "");
        try {
            UUID userId = UUID.fromString(userIdStr);
            UserActivityRealtimeEvent event = objectMapper.readValue(message.getBody(), UserActivityRealtimeEvent.class);
            realtimeService.broadcast(userId, event);
        } catch (IllegalArgumentException ex) {
            log.warn("[ACTIVITY-SUB] bad userId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[ACTIVITY-SUB] failed: {}", ex.getMessage());
        }
    }
}
