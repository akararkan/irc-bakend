package ak.dev.irc.app.audit.controller;

import ak.dev.irc.app.audit.cassandra.entity.AuditLogByUserEntity;
import ak.dev.irc.app.audit.cassandra.repository.AuditLogByUserRepository;
import ak.dev.irc.app.audit.dto.AuditLogResponse;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import ak.dev.irc.app.audit.realtime.AuditRealtimeService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only access to the audit log (Cassandra-backed).
 *
 * <p>Endpoints preserved at their original paths:</p>
 * <ul>
 *   <li>{@code GET /api/v1/admin/audit?userId=…} — Cassandra needs a scope:
 *       pass {@code userId}. Other filters ({@code operation}, {@code outcome},
 *       {@code from}, {@code to}) are applied in-memory to the returned slice.
 *       A request without {@code userId} returns 400 — use the SSE stream
 *       for a global view.</li>
 *   <li>{@code GET /api/v1/admin/audit/users/{userId}} — per-user history,
 *       cursor-paginated.</li>
 *   <li>{@code GET /api/v1/admin/audit/stream} — realtime SSE feed, unchanged
 *       (already runs through Redis pub/sub).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AuditLogController {

    private static final int DEFAULT_PAGE = 50;

    private final AuditLogByUserRepository userRepo;
    private final AuditRealtimeService     realtimeService;

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) AuditOperation operation,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int pageSize,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor) {

        if (userId == null) return ResponseEntity.badRequest().build();

        List<AuditLogByUserEntity> rows = cursor == null
                ? userRepo.firstPage(userId, pageSize)
                : userRepo.nextPage(userId, cursor.toInstant(ZoneOffset.UTC), pageSize);

        Instant fromI = from == null ? null : from.toInstant(ZoneOffset.UTC);
        Instant toI   = to   == null ? null : to.toInstant(ZoneOffset.UTC);

        List<AuditLogResponse> filtered = rows.stream()
                .filter(r -> operation == null || operation.name().equals(r.getOperation()))
                .filter(r -> outcome   == null || outcome.name().equals(r.getOutcome()))
                .filter(r -> fromI == null || !r.getCreatedAt().isBefore(fromI))
                .filter(r -> toI   == null || !r.getCreatedAt().isAfter(toI))
                .map(AuditLogController::toResponse)
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<List<AuditLogResponse>> userHistory(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int pageSize,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor) {
        List<AuditLogByUserEntity> rows = cursor == null
                ? userRepo.firstPage(userId, pageSize)
                : userRepo.nextPage(userId, cursor.toInstant(ZoneOffset.UTC), pageSize);
        return ResponseEntity.ok(rows.stream().map(AuditLogController::toResponse).toList());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        UUID adminId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException("Admin authentication required"));
        return realtimeService.subscribe(adminId);
    }

    private static AuditLogResponse toResponse(AuditLogByUserEntity r) {
        return new AuditLogResponse(
                r.getAuditId(),
                r.getUserId(),
                r.getUsername(),
                r.getOperation() == null ? null : AuditOperation.valueOf(r.getOperation()),
                r.getOutcome()   == null ? null : AuditOutcome.valueOf(r.getOutcome()),
                r.getResourceType(),
                r.getResourceId(),
                r.getHttpMethod(),
                r.getPath(),
                r.getQueryString(),
                r.getStatusCode(),
                r.getDurationMs(),
                r.getIpAddress(),
                r.getUserAgent(),
                r.getSummary(),
                r.getErrorCode(),
                r.getCreatedAt() == null ? null
                        : LocalDateTime.ofInstant(r.getCreatedAt(), ZoneOffset.UTC));
    }
}
