package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.admin.impersonation.ImpersonationService;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkActionRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkActionResult;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkCreateRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkRowResult;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminCreateUserRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminEditUserRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminInviteRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminInviteResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminLoginEventRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminModerationSummary;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPasswordResetRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPasswordResetResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPiiResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminReasonRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminSessionRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminSettingsAuditRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminStrikeRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminStrikeRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserAnalyticsResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserDataResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserDetail;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserRow;
import ak.dev.irc.app.user.dto.request.AdminChangeRoleRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin user administration — the C/E/S/X action families of
 * docs/admin/user-administration.md plus the read surface of users-roles.md
 * (blueprint §3.1). Double-gated: the {@code /api/v1/admin/**} filter-chain
 * rule plus the class-level annotation; danger high/critical handlers
 * additionally carry {@link RequiresStepUp}.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final ImpersonationService impersonationService;

    // ── reads ───────────────────────────────────────────────────────────
    // §6 matrix: MODERATOR and SUPPORT get the inspection READS (masked
    // email, no PII reveal); every mutation below stays ADMIN-only via the
    // class-level grant.

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")
    public ResponseEntity<Page<AdminUserRow>> directory(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.directory(q, role, status, verified, from, to, pageable));
    }

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT','ANALYST')")
    public ResponseEntity<AdminUserAnalyticsResponse> analytics(
            @RequestParam(defaultValue = "90") int window) {
        return ResponseEntity.ok(adminUserService.analytics(window));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")
    public ResponseEntity<AdminUserDetail> detail(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.detail(userId));
    }

    @GetMapping("/{userId}/pii")
    @RequiresStepUp
    public ResponseEntity<AdminPiiResponse> pii(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.pii(userId));
    }

    @GetMapping("/{userId}/sessions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public ResponseEntity<List<AdminSessionRow>> sessions(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.sessions(userId));
    }

    @GetMapping("/{userId}/login-events")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")
    public ResponseEntity<Page<AdminLoginEventRow>> loginEvents(
            @PathVariable UUID userId, @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.loginEvents(userId, pageable));
    }

    @GetMapping("/{userId}/settings-audit")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")
    public ResponseEntity<Page<AdminSettingsAuditRow>> settingsAudit(
            @PathVariable UUID userId, @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.settingsAudit(userId, pageable));
    }

    @GetMapping("/{userId}/moderation")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")
    public ResponseEntity<AdminModerationSummary> moderation(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.moderation(userId));
    }

    @GetMapping("/{userId}/data")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public ResponseEntity<AdminUserDataResponse> data(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.data(userId));
    }

    // ── role / state ────────────────────────────────────────────────────

    @PatchMapping("/{userId}/role")
    @RequiresStepUp
    public ResponseEntity<UserResponse> changeRole(@PathVariable UUID userId,
                                                   @Valid @RequestBody AdminChangeRoleRequest request) {
        return ResponseEntity.ok(adminUserService.changeRole(userId, request));
    }

    @PostMapping("/{userId}/disable")
    @RequiresStepUp
    public ResponseEntity<Void> disable(@PathVariable UUID userId,
                                        @RequestBody(required = false) AdminReasonRequest body) {
        adminUserService.disable(userId, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/enable")
    @RequiresStepUp
    public ResponseEntity<Void> enable(@PathVariable UUID userId) {
        adminUserService.enable(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/lock")
    @RequiresStepUp
    public ResponseEntity<Void> lock(@PathVariable UUID userId,
                                     @RequestBody(required = false) AdminReasonRequest body) {
        adminUserService.lock(userId, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/unlock")
    @RequiresStepUp
    public ResponseEntity<Void> unlock(@PathVariable UUID userId) {
        adminUserService.unlock(userId);
        return ResponseEntity.noContent().build();
    }

    // ── sessions ────────────────────────────────────────────────────────

    @DeleteMapping("/{userId}/sessions/{sid}")
    @RequiresStepUp
    public ResponseEntity<Void> revokeSession(@PathVariable UUID userId, @PathVariable UUID sid) {
        adminUserService.revokeSession(userId, sid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/sessions/revoke-all")
    @RequiresStepUp
    public ResponseEntity<Map<String, Integer>> revokeAllSessions(@PathVariable UUID userId) {
        return ResponseEntity.ok(Map.of("revoked", adminUserService.revokeAllSessions(userId)));
    }

    // ── credentials ─────────────────────────────────────────────────────

    @PostMapping("/{userId}/password/reset")
    @RequiresStepUp
    public ResponseEntity<AdminPasswordResetResponse> resetPassword(
            @PathVariable UUID userId,
            @RequestBody(required = false) @Valid AdminPasswordResetRequest body) {
        return ResponseEntity.ok(adminUserService.resetPassword(userId, body));
    }

    @PostMapping("/{userId}/2fa/reset")
    @RequiresStepUp
    public ResponseEntity<Void> reset2fa(@PathVariable UUID userId,
                                         @RequestBody(required = false) AdminReasonRequest body) {
        adminUserService.reset2fa(userId, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/email/verify")
    @RequiresStepUp
    public ResponseEntity<Void> markEmailVerified(@PathVariable UUID userId) {
        adminUserService.markEmailVerified(userId);
        return ResponseEntity.noContent().build();
    }

    // ── provisioning ────────────────────────────────────────────────────

    @PostMapping
    @RequiresStepUp
    public ResponseEntity<AdminUserDetail> create(@Valid @RequestBody AdminCreateUserRequest request) {
        return ResponseEntity.status(201).body(adminUserService.create(request));
    }

    @PostMapping("/bulk")
    @RequiresStepUp
    public ResponseEntity<List<AdminBulkRowResult>> bulkCreate(
            @Valid @RequestBody AdminBulkCreateRequest request) {
        return ResponseEntity.ok(adminUserService.bulkCreate(request));
    }

    @PostMapping("/invite")
    @RequiresStepUp
    public ResponseEntity<AdminInviteResponse> invite(@Valid @RequestBody AdminInviteRequest request) {
        return ResponseEntity.status(201).body(adminUserService.invite(request));
    }

    @PostMapping("/invite/{inviteId}/resend")
    public ResponseEntity<AdminInviteResponse> resendInvite(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(adminUserService.resendInvite(inviteId));
    }

    @DeleteMapping("/invite/{inviteId}")
    public ResponseEntity<Void> revokeInvite(@PathVariable UUID inviteId) {
        adminUserService.revokeInvite(inviteId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{userId}")
    @RequiresStepUp
    public ResponseEntity<UserResponse> edit(@PathVariable UUID userId,
                                             @Valid @RequestBody AdminEditUserRequest request) {
        return ResponseEntity.ok(adminUserService.edit(userId, request));
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    @PostMapping("/{userId}/deletion/request")
    @RequiresStepUp
    public ResponseEntity<AdminUserDataResponse> requestDeletion(
            @PathVariable UUID userId,
            @RequestBody(required = false) AdminReasonRequest body) {
        return ResponseEntity.ok(adminUserService.requestDeletion(
                userId, body != null ? body.reason() : null));
    }

    @PostMapping("/{userId}/deletion/cancel")
    @RequiresStepUp
    public ResponseEntity<AdminUserDataResponse> cancelDeletion(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.cancelDeletion(userId));
    }

    @PostMapping("/{userId}/purge/now")
    @RequiresStepUp
    public ResponseEntity<AdminUserDataResponse> purgeNow(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminUserService.purgeNow(userId));
    }

    @PostMapping("/{userId}/purge/hold")
    @RequiresStepUp
    public ResponseEntity<AdminUserDataResponse> purgeHold(@PathVariable UUID userId,
                                                           @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(adminUserService.holdPurge(userId, days));
    }

    // ── moderation / bulk / impersonation ───────────────────────────────

    @PostMapping("/{userId}/strikes")
    @RequiresStepUp
    public ResponseEntity<AdminStrikeRow> issueStrike(@PathVariable UUID userId,
                                                      @Valid @RequestBody AdminStrikeRequest request) {
        return ResponseEntity.status(201).body(adminUserService.issueStrike(userId, request));
    }

    @PostMapping("/bulk-action")
    @RequiresStepUp
    public ResponseEntity<List<AdminBulkActionResult>> bulkAction(
            @Valid @RequestBody AdminBulkActionRequest request) {
        return ResponseEntity.ok(adminUserService.bulkAction(request));
    }

    @PostMapping("/{userId}/impersonate")
    @RequiresStepUp
    public ResponseEntity<ImpersonationService.ImpersonationGrant> impersonate(
            @PathVariable UUID userId,
            @RequestBody AdminReasonRequest body,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(impersonationService.start(
                admin, userId, body != null ? body.reason() : null));
    }
}
