package ak.dev.irc.app.admin.activity;

import ak.dev.irc.app.activity.cassandra.repository.ReelViewCassandraRepository;
import ak.dev.irc.app.activity.cassandra.repository.UserActivityByTypeRepository;
import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Break-glass activity forensics (blueprint §3.15, activity-engagement.md).
 * The privacy contract is enforced in code, not prose: per-user timeline /
 * histogram / reel-history / export reads 403 WITHOUT an OPEN dual-control
 * case for that user (opened by one admin, approved by a different one,
 * auto-expiring) — plus step-up, plus a loud audit row per read. The one
 * routinely-available per-user action is ERASURE, which honors the
 * user-deletable contract instead of violating it.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminActivityController {

    private final BreakGlassCaseRepository caseRepository;
    private final UserActivityService userActivityService;
    private final UserActivityByTypeRepository byTypeRepository;
    private final ReelViewCassandraRepository reelViewRepository;
    private final AdminAuditor adminAuditor;

    public record OpenCaseBody(@NotBlank String kind,
                               @NotBlank @Size(min = 10, max = 1000) String reason,
                               @Size(max = 120) String caseRef) {
    }

    public record EraseBody(String type, @Size(max = 500) String reason) {
    }

    // ── break-glass case lifecycle ──────────────────────────────────────

    @PostMapping("/breakglass/{targetUserId}")
    @RequiresStepUp
    public ResponseEntity<BreakGlassCase> openCase(@PathVariable UUID targetUserId,
                                                   @RequestBody OpenCaseBody body,
                                                   @AuthenticationPrincipal User admin) {
        BreakGlassCase.CaseKind kind;
        try {
            kind = BreakGlassCase.CaseKind.valueOf(body.kind().trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown kind. Allowed: "
                    + java.util.Arrays.toString(BreakGlassCase.CaseKind.values()), "INVALID_KIND");
        }
        BreakGlassCase saved = caseRepository.save(BreakGlassCase.builder()
                .targetUserId(targetUserId)
                .kind(kind)
                .reason(body.reason().trim())
                .caseRef(body.caseRef())
                .openedBy(admin.getId())
                .build());
        adminAuditor.record(AuditOperation.CREATE, "User", targetUserId,
                "ADMIN_BREAKGLASS_CASE_OPEN", kind + " — " + body.reason().trim());
        return ResponseEntity.status(201).body(saved);
    }

    /** Dual control: the approver must be a DIFFERENT admin than the opener. */
    @PostMapping("/breakglass/cases/{caseId}/approve")
    @RequiresStepUp
    public ResponseEntity<BreakGlassCase> approveCase(@PathVariable UUID caseId,
                                                      @AuthenticationPrincipal User admin) {
        BreakGlassCase bgCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("BreakGlassCase", "id", caseId));
        if (bgCase.getStatus() != BreakGlassCase.Status.PENDING_APPROVAL) {
            throw new BadRequestException("Case is not pending approval.", "CASE_NOT_PENDING");
        }
        if (admin.getId().equals(bgCase.getOpenedBy())) {
            throw new ForbiddenException(
                    "Dual control: a break-glass case cannot be approved by the admin who opened it.",
                    "DUAL_CONTROL_REQUIRED");
        }
        bgCase.setStatus(BreakGlassCase.Status.OPEN);
        bgCase.setApprovedBy(admin.getId());
        bgCase.setApprovedAt(LocalDateTime.now());
        adminAuditor.record(AuditOperation.UPDATE, "User", bgCase.getTargetUserId(),
                "ADMIN_BREAKGLASS_CASE_APPROVE", "case=" + caseId);
        return ResponseEntity.ok(caseRepository.save(bgCase));
    }

    @PostMapping("/breakglass/cases/{caseId}/close")
    public ResponseEntity<BreakGlassCase> closeCase(@PathVariable UUID caseId) {
        BreakGlassCase bgCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("BreakGlassCase", "id", caseId));
        bgCase.setStatus(BreakGlassCase.Status.CLOSED);
        bgCase.setClosedAt(LocalDateTime.now());
        adminAuditor.record(AuditOperation.UPDATE, "User", bgCase.getTargetUserId(),
                "ADMIN_BREAKGLASS_CASE_CLOSE", "case=" + caseId);
        return ResponseEntity.ok(caseRepository.save(bgCase));
    }

    @GetMapping("/breakglass/cases")
    public ResponseEntity<Page<BreakGlassCase>> cases(@PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(caseRepository.findAllByOrderByOpenedAtDesc(
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    // ── break-glass reads (A1-A3, A5) ───────────────────────────────────

    @GetMapping("/users/{userId}/activity")
    @RequiresStepUp
    public ResponseEntity<Page<UserActivityResponse>> activity(
            @PathVariable UUID userId,
            @RequestParam(required = false) List<UserActivityType> types,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25) Pageable pageable) {
        requireOpenCase(userId, "ADMIN_ACTIVITY_BREAKGLASS_VIEW");
        return ResponseEntity.ok(userActivityService.listMyActivity(userId, types,
                from == null ? null : from.toInstant(ZoneOffset.UTC),
                to == null ? null : to.toInstant(ZoneOffset.UTC),
                PageRequest.of(0, Pages.clamp(pageable.getPageSize()))));
    }

    @GetMapping("/users/{userId}/activity/summary")
    @RequiresStepUp
    public ResponseEntity<Map<String, Long>> activitySummary(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "90") int window) {
        requireOpenCase(userId, "ADMIN_ACTIVITY_SUMMARY_VIEW");
        Instant from = Instant.now().minus(java.time.Duration.ofDays(
                Math.max(1, Math.min(window, 365))));
        Map<String, Long> histogram = new LinkedHashMap<>();
        for (UserActivityType type : UserActivityType.values()) {
            try {
                long n = byTypeRepository.countForSince(userId, type.name(), from);
                if (n > 0) histogram.put(type.name(), n);
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.ok(histogram);
    }

    @GetMapping("/users/{userId}/reels/watched")
    @RequiresStepUp
    public ResponseEntity<List<Map<String, Object>>> reelsWatched(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor) {
        requireOpenCase(userId, "ADMIN_REELVIEWS_BREAKGLASS_VIEW");
        var rows = cursor == null
                ? reelViewRepository.firstPage(userId, Pages.clamp(pageSize))
                : reelViewRepository.nextPage(userId, cursor.toInstant(ZoneOffset.UTC),
                        Pages.clamp(pageSize));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reelViewId", r.getReelViewId());
            row.put("postId", r.getPostId());
            row.put("watchedSeconds", r.getWatchedSeconds());
            row.put("watchedAt", r.getCreatedAt());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Case-evidence export of the visible window — PII egress, loudly audited. */
    @GetMapping("/users/{userId}/activity/export")
    @RequiresStepUp
    public ResponseEntity<?> export(@PathVariable UUID userId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                    @RequestParam(defaultValue = "json") String format) {
        requireOpenCase(userId, "ADMIN_ACTIVITY_EXPORT");
        Page<UserActivityResponse> page = userActivityService.listMyActivity(userId, null,
                from == null ? null : from.toInstant(ZoneOffset.UTC),
                to == null ? null : to.toInstant(ZoneOffset.UTC),
                PageRequest.of(0, 1000));
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder("activityId,type,createdAt\n");
            for (UserActivityResponse r : page.getContent()) {
                csv.append(r.getId()).append(',').append(r.getActivityType())
                        .append(',').append(r.getCreatedAt()).append('\n');
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition",
                            "attachment; filename=\"activity-" + userId + ".csv\"")
                    .body(csv.toString());
        }
        return ResponseEntity.ok(page.getContent());
    }

    // ── erasure (A4 — routinely available; honors user-deletable) ───────

    @PostMapping("/users/{userId}/activity/erase")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> erase(@PathVariable UUID userId,
                                                     @RequestBody(required = false) EraseBody body) {
        UserActivityType type = null;
        if (body != null && body.type() != null && !body.type().isBlank()) {
            try {
                type = UserActivityType.valueOf(body.type().trim().toUpperCase());
            } catch (Exception e) {
                throw new BadRequestException("Unknown activity type.", "INVALID_TYPE");
            }
        }
        int deleted = userActivityService.deleteAll(userId, type);
        adminAuditor.record(AuditOperation.DELETE, "User", userId, "ADMIN_ACTIVITY_ERASE",
                (type == null ? "all types" : type.name()) + ", rows=" + deleted
                        + (body != null && body.reason() != null ? " — " + body.reason() : ""));
        return ResponseEntity.ok(Map.of("deleted", deleted,
                "note", "hard cap 10k rows per call — repeat for heavier histories"));
    }

    // ── the gate ────────────────────────────────────────────────────────

    private void requireOpenCase(UUID targetUserId, String auditAction) {
        boolean open = caseRepository
                .findByTargetUserIdAndStatus(targetUserId, BreakGlassCase.Status.OPEN)
                .stream().anyMatch(BreakGlassCase::isOpenNow);
        if (!open) {
            throw new ForbiddenException(
                    "Break-glass access requires an OPEN dual-control case for this user "
                            + "(POST /api/v1/admin/breakglass/{userId}, approved by a second admin). "
                            + "The default is no access.",
                    "BREAKGLASS_CASE_REQUIRED");
        }
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        adminAuditor.record(AuditOperation.READ, "User", targetUserId, auditAction,
                "admin=" + adminId);
    }
}
