package ak.dev.irc.app.audit.service;

import ak.dev.irc.app.audit.cassandra.entity.AuditLogByResourceEntity;
import ak.dev.irc.app.audit.cassandra.entity.AuditLogByUserEntity;
import ak.dev.irc.app.audit.cassandra.repository.AuditLogByResourceRepository;
import ak.dev.irc.app.audit.cassandra.repository.AuditLogByUserRepository;
import ak.dev.irc.app.audit.dto.AuditLogResponse;
import ak.dev.irc.app.audit.entity.AuditLog;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import ak.dev.irc.app.audit.mapper.AuditLogMapper;
import ak.dev.irc.app.audit.realtime.AuditRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Single entry point for audit-log writes — Cassandra-backed.
 *
 * <p>The hot path is {@link #recordAsync}: non-blocking, never throws,
 * persists to two denormalized Cassandra tables (one keyed by user for
 * "what did U do?" queries, one keyed by (resource_type, resource_id) for
 * "what happened to this resource?"). The realtime broadcast fires once
 * both writes commit so admin SSE listeners see the row instantly.</p>
 *
 * <p>180-day retention is enforced at the table level via Cassandra TTL —
 * no cleanup job needed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogByUserRepository     userRepo;
    private final AuditLogByResourceRepository resourceRepo;
    private final AuditLogMapper               mapper;
    private final AuditRealtimePublisher       publisher;

    /**
     * Convenience helper for service-layer code that wants to log a specific
     * business action ("user followed user") without going through the HTTP
     * interceptor path.
     */
    public void record(UUID userId,
                       String username,
                       AuditOperation operation,
                       String resourceType,
                       UUID resourceId,
                       String summary) {
        recordAsync(AuditLog.builder()
                .userId(userId)
                .username(username)
                .operation(operation == null ? AuditOperation.SYSTEM : operation)
                .outcome(AuditOutcome.SUCCESS)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .summary(summary)
                .build());
    }

    /**
     * Persist on the audit executor pool — never blocks the request that
     * triggered this audit. Failure is logged and swallowed; audit gaps are
     * acceptable but audit failures must NEVER propagate.
     */
    @Async("auditExecutor")
    public void recordAsync(AuditLog draft) {
        try {
            // Stamp id + createdAt at write time so the caller doesn't have to.
            UUID id  = draft.getId() != null ? draft.getId() : UUID.randomUUID();
            LocalDateTime ts = draft.getCreatedAt() != null
                    ? draft.getCreatedAt()
                    : LocalDateTime.now(ZoneOffset.UTC);
            draft.setId(id);
            draft.setCreatedAt(ts);

            Instant tsInstant = ts.toInstant(ZoneOffset.UTC);

            // Anonymous traffic (no user_id) → only write the resource partition.
            // The user-partition table doesn't accept null partition keys.
            if (draft.getUserId() != null) {
                userRepo.save(toByUser(draft, id, tsInstant));
            }

            // Write the resource partition only if we have a (type, id) tuple.
            if (draft.getResourceType() != null && draft.getResourceId() != null) {
                resourceRepo.save(toByResource(draft, id, tsInstant));
            }

            AuditLogResponse payload = mapper.toResponse(draft);
            publisher.publish(payload);
        } catch (Exception ex) {
            log.error("[AUDIT] Failed to persist audit row: {}", ex.getMessage(), ex);
        }
    }

    // ── Cassandra-row builders ──────────────────────────────────────────────

    private AuditLogByUserEntity toByUser(AuditLog d, UUID id, Instant ts) {
        return AuditLogByUserEntity.builder()
                .userId(d.getUserId()).createdAt(ts).auditId(id)
                .username(d.getUsername())
                .operation(d.getOperation() == null ? null : d.getOperation().name())
                .outcome  (d.getOutcome()   == null ? null : d.getOutcome().name())
                .resourceType(d.getResourceType()).resourceId(d.getResourceId())
                .httpMethod(d.getHttpMethod()).path(d.getPath())
                .queryString(d.getQueryString())
                .statusCode(d.getStatusCode()).durationMs(d.getDurationMs())
                .ipAddress(d.getIpAddress()).userAgent(d.getUserAgent())
                .summary(d.getSummary()).errorCode(d.getErrorCode())
                .build();
    }

    private AuditLogByResourceEntity toByResource(AuditLog d, UUID id, Instant ts) {
        return AuditLogByResourceEntity.builder()
                .resourceType(d.getResourceType()).resourceId(d.getResourceId())
                .createdAt(ts).auditId(id)
                .userId(d.getUserId()).username(d.getUsername())
                .operation(d.getOperation() == null ? null : d.getOperation().name())
                .outcome  (d.getOutcome()   == null ? null : d.getOutcome().name())
                .httpMethod(d.getHttpMethod()).path(d.getPath())
                .queryString(d.getQueryString())
                .statusCode(d.getStatusCode()).durationMs(d.getDurationMs())
                .ipAddress(d.getIpAddress()).userAgent(d.getUserAgent())
                .summary(d.getSummary()).errorCode(d.getErrorCode())
                .build();
    }
}
