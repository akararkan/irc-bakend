package ak.dev.irc.app.audit.service;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import ak.dev.irc.app.audit.entity.AuditLog;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import ak.dev.irc.app.audit.mapper.AuditLogMapper;
import ak.dev.irc.app.audit.realtime.AuditRealtimePublisher;
import ak.dev.irc.app.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Single entry point for audit-log writes.
 *
 * <p>{@link #recordAsync} is the hot path — non-blocking, never throws,
 * persists in its own transaction so a rolled-back business transaction
 * still leaves an audit trail of the attempt. The realtime broadcast
 * fires only after the audit row commits.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository       repo;
    private final AuditLogMapper           mapper;
    private final AuditRealtimePublisher   publisher;

    /**
     * Build-and-fire helper for service-layer code that wants to log a
     * specific business action ("user followed user") without going through
     * the HTTP interceptor.
     */
    public void record(UUID userId,
                       String username,
                       AuditOperation operation,
                       String resourceType,
                       UUID resourceId,
                       String summary) {
        AuditLog draft = AuditLog.builder()
                .userId(userId)
                .username(username)
                .operation(operation == null ? AuditOperation.SYSTEM : operation)
                .outcome(AuditOutcome.SUCCESS)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .summary(summary)
                .build();
        recordAsync(draft);
    }

    /**
     * Persist the row in its own transaction (REQUIRES_NEW) so that even when
     * the surrounding business transaction rolls back, the audit trail is
     * preserved — including the failure that caused the rollback.
     */
    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAsync(AuditLog draft) {
        try {
            AuditLog saved = repo.save(draft);
            AuditLogResponse payload = mapper.toResponse(saved);
            // Realtime fan-out — every connected admin (across any instance)
            // sees the row land within milliseconds.
            publisher.publish(payload);
        } catch (Exception ex) {
            // Audit failure must never propagate. Log and move on.
            log.error("[AUDIT] Failed to persist audit row: {}", ex.getMessage(), ex);
        }
    }
}
