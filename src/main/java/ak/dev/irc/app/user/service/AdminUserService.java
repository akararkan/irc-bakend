package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.AdminUserDtos.AcceptInviteRequest;
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
import ak.dev.irc.app.user.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The full admin user-administration surface (blueprint §3.1 —
 * docs/admin/user-administration.md + users-roles.md): directory & inspection
 * reads, provisioning (single/bulk/invite), identity & credential control,
 * account state, sessions, lifecycle, strikes and bulk operations.
 */
public interface AdminUserService {

    // ── reads ───────────────────────────────────────────────────────────
    Page<AdminUserRow> directory(String q, Role role, String status, Boolean verified,
                                 LocalDateTime from, LocalDateTime to, Pageable pageable);

    AdminUserDetail detail(UUID userId);

    AdminPiiResponse pii(UUID userId);

    List<AdminSessionRow> sessions(UUID userId);

    Page<AdminLoginEventRow> loginEvents(UUID userId, Pageable pageable);

    Page<AdminSettingsAuditRow> settingsAudit(UUID userId, Pageable pageable);

    AdminModerationSummary moderation(UUID userId);

    AdminUserDataResponse data(UUID userId);

    AdminUserAnalyticsResponse analytics(int windowDays);

    // ── role / state ────────────────────────────────────────────────────
    UserResponse changeRole(UUID targetUserId, AdminChangeRoleRequest request);

    void disable(UUID userId, String reason);

    void enable(UUID userId);

    void lock(UUID userId, String reason);

    void unlock(UUID userId);

    // ── sessions ────────────────────────────────────────────────────────
    void revokeSession(UUID userId, UUID sid);

    int revokeAllSessions(UUID userId);

    // ── credentials ─────────────────────────────────────────────────────
    AdminPasswordResetResponse resetPassword(UUID userId, AdminPasswordResetRequest request);

    void reset2fa(UUID userId, String reason);

    void markEmailVerified(UUID userId);

    // ── provisioning ────────────────────────────────────────────────────
    AdminUserDetail create(AdminCreateUserRequest request);

    List<AdminBulkRowResult> bulkCreate(AdminBulkCreateRequest request);

    AdminInviteResponse invite(AdminInviteRequest request);

    AdminInviteResponse resendInvite(UUID inviteId);

    void revokeInvite(UUID inviteId);

    UserResponse acceptInvite(AcceptInviteRequest request);

    UserResponse edit(UUID userId, AdminEditUserRequest request);

    // ── lifecycle / moderation / bulk ───────────────────────────────────
    AdminUserDataResponse requestDeletion(UUID userId, String reason);

    AdminUserDataResponse cancelDeletion(UUID userId);

    AdminUserDataResponse purgeNow(UUID userId);

    AdminUserDataResponse holdPurge(UUID userId, int days);

    AdminStrikeRow issueStrike(UUID userId, AdminStrikeRequest request);

    List<AdminBulkActionResult> bulkAction(AdminBulkActionRequest request);
}
