package ak.dev.irc.app.audit.realtime;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a serialised {@link AuditLogResponse} to a single global Redis
 * pub/sub channel — admin SSE clients subscribe to this channel via
 * {@link AuditRealtimeSubscriber} and forward the row to their local emitter.
 *
 * <p>Single channel (not per-user) because every audit row needs to reach
 * every connected admin instance.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRealtimePublisher {

    public static final String CHANNEL = "irc:audit:stream";

    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    public void publish(AuditLogResponse log) {
        try {
            String json = objectMapper.writeValueAsString(log);
            redis.convertAndSend(CHANNEL, json);
        } catch (Exception ex) {
            // Audit pub failure must never break the request flow.
            AuditRealtimePublisher.log.debug("[AUDIT-PUB] failed: {}", ex.getMessage());
        }
    }
}
