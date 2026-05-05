package ak.dev.irc.app.audit.realtime;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Forwards audit rows from the Redis pub/sub channel to the local SSE
 * service so every running instance can serve any connected admin.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRealtimeSubscriber implements MessageListener {

    private final AuditRealtimeService realtimeService;
    private final ObjectMapper         objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        try {
            AuditLogResponse log = objectMapper.readValue(message.getBody(), AuditLogResponse.class);
            realtimeService.broadcast(log);
        } catch (Exception ex) {
            AuditRealtimeSubscriber.log.error("[AUDIT-SUB] failed to process message: {}", ex.getMessage());
        }
    }
}
