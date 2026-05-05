package ak.dev.irc.app.audit.controller;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import ak.dev.irc.app.audit.mapper.AuditLogMapper;
import ak.dev.irc.app.audit.realtime.AuditRealtimeService;
import ak.dev.irc.app.audit.repository.AuditLogRepository;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-only access to the audit log. Three modes:
 * <ul>
 *   <li>{@code GET /api/v1/admin/audit} — paged search with optional filters.</li>
 *   <li>{@code GET /api/v1/admin/audit/users/{userId}} — full activity history.</li>
 *   <li>{@code GET /api/v1/admin/audit/stream} — SSE feed of every audit row in
 *       real time across the entire cluster.</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize} gates the entire controller — only ADMIN /
 * SUPER_ADMIN see audit data. Any other request returns 403.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AuditLogController {

    private final AuditLogRepository    repo;
    private final AuditLogMapper        mapper;
    private final AuditRealtimeService  realtimeService;

    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) AuditOperation operation,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<AuditLogResponse> page = repo.search(
                userId, operation, outcome, resourceType, resourceId, from, to, pageable)
                .map(mapper::toResponse);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<Page<AuditLogResponse>> userHistory(
            @PathVariable UUID userId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<AuditLogResponse> page = repo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(mapper::toResponse);
        return ResponseEntity.ok(page);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        UUID adminId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException("Admin authentication required"));
        return realtimeService.subscribe(adminId);
    }
}
