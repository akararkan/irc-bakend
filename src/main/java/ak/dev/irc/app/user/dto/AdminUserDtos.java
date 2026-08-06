package ak.dev.irc.app.user.dto;

import ak.dev.irc.app.user.dto.response.BadgeDto;
import ak.dev.irc.app.user.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs of the admin user-administration surface
 * (docs/admin/user-administration.md + users-roles.md, blueprint §3.1).
 * All responses are {@code NON_NULL} records; no secret column
 * (password, 2FA secret, token values, OTP hashes) ever appears here.
 */
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    // ── directory / detail ──────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserRow(UUID id,
                               String username,
                               String displayName,
                               String email,
                               String avatarUrl,
                               Role role,
                               List<BadgeDto> badges,
                               boolean emailVerified,
                               boolean phoneVerified,
                               boolean twoFactorEnabled,
                               boolean enabled,
                               boolean locked,
                               String status,
                               LocalDateTime createdAt,
                               LocalDateTime lastLoginAt,
                               LocalDateTime deletedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserDetail(AdminUserRow user,
                                  String fname,
                                  String lname,
                                  String bio,
                                  String location,
                                  String academicTitle,
                                  String institutionName,
                                  String websiteUrl,
                                  String orcidId,
                                  String preferredLanguage,
                                  String timezone,
                                  AdminUserStats stats,
                                  AdminStorage storage,
                                  AdminDeletionState deletion,
                                  String lastAction,
                                  String actionNote,
                                  LocalDateTime updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserStats(long postCount, long reelCount, long researchCount,
                                 long questionCount, long followerCount, long followingCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminStorage(long totalBytes, Map<String, Long> byType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminDeletionState(String status, LocalDateTime requestedAt,
                                     LocalDateTime purgeAfter, LocalDateTime resolvedAt) {
    }

    /** Raw PII — step-up-gated reveal (`ADMIN_PII_REVEAL`). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminPiiResponse(String email, LocalDateTime emailVerifiedAt,
                                   String phoneE164, LocalDateTime phoneVerifiedAt) {
    }

    // ── create / bulk / invite ──────────────────────────────────────────

    public record AdminCreateUserRequest(@NotBlank @Size(max = 80) String fname,
                                         @NotBlank @Size(max = 80) String lname,
                                         @NotBlank @Size(min = 3, max = 50) String username,
                                         @NotBlank @Email @Size(max = 255) String email,
                                         Role role,
                                         @Size(min = 8, max = 128) String temporaryPassword,
                                         Boolean sendInvite,
                                         Boolean markEmailVerified) {
    }

    public record AdminBulkCreateRequest(@NotNull @Size(min = 1, max = 1000)
                                         List<AdminCreateUserRequest> rows) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminBulkRowResult(int index, String username, String email,
                                     String outcome, UUID userId, String error) {
    }

    public record AdminInviteRequest(@NotBlank @Email String email, Role role) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminInviteResponse(UUID id, String email, Role role,
                                      LocalDateTime expiresAt, LocalDateTime usedAt,
                                      LocalDateTime createdAt) {
    }

    /** Public invite-acceptance body (the set-password link target). */
    public record AcceptInviteRequest(@NotBlank String token,
                                      @NotBlank @Size(max = 80) String fname,
                                      @NotBlank @Size(max = 80) String lname,
                                      @NotBlank @Size(min = 3, max = 50) String username,
                                      @NotBlank @Size(min = 8, max = 128) String password) {
    }

    // ── edit / credentials / state ──────────────────────────────────────

    public record AdminEditUserRequest(@Size(max = 80) String fname,
                                       @Size(max = 80) String lname,
                                       @Size(min = 3, max = 50) String username,
                                       @Email @Size(max = 255) String email) {
    }

    public record AdminPasswordResetRequest(@Size(min = 8, max = 128) String temporaryPassword) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminPasswordResetResponse(String temporaryPassword, int sessionsRevoked) {
    }

    public record AdminReasonRequest(@Size(max = 500) String reason) {
    }

    public record AdminStrikeRequest(UUID reportId, @NotBlank @Size(max = 200) String reason) {
    }

    // ── sessions / logs ─────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminSessionRow(UUID sid, String deviceName, String platform, String ip,
                                  LocalDateTime lastSeenAt, LocalDateTime createdAt,
                                  LocalDateTime expiresAt, boolean trusted,
                                  boolean revoked, LocalDateTime revokedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminLoginEventRow(String ip, String userAgent, String method,
                                     String outcome, LocalDateTime ts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminSettingsAuditRow(String settingKey, String oldValue, String newValue,
                                        String ip, LocalDateTime createdAt) {
    }

    // ── moderation / data lifecycle ─────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminStrikeRow(UUID id, UUID reportId, String reason, boolean active,
                                 LocalDateTime issuedAt, LocalDateTime expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminReportRow(UUID id, String targetType, UUID targetId, String reason,
                                 String state, String resolution, LocalDateTime createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminModerationSummary(long activeStrikes,
                                         List<AdminStrikeRow> strikes,
                                         List<AdminReportRow> reportsAgainst,
                                         List<AdminReportRow> reportsFiled) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminExportJobRow(UUID id, String status, Long sizeBytes,
                                    LocalDateTime createdAt, LocalDateTime readyAt,
                                    LocalDateTime expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserDataResponse(List<AdminExportJobRow> exportJobs,
                                        AdminDeletionState deletion,
                                        boolean tombstoned) {
    }

    // ── analytics / bulk-action ─────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DayCount(String day, long count) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserAnalyticsResponse(long totalUsers,
                                             long signupsToday,
                                             long signups7d,
                                             Map<String, Long> roleDistribution,
                                             double emailVerifiedPct,
                                             double phoneVerifiedPct,
                                             double twoFactorPct,
                                             Map<String, Long> deletionPipeline,
                                             List<DayCount> signupsByDay,
                                             Map<String, Object> closeFriends,
                                             Map<String, Object> sessions) {
    }

    public record AdminBulkActionRequest(@NotNull @Size(min = 1, max = 100) List<UUID> ids,
                                         @NotBlank String action,
                                         Role role,
                                         @Size(max = 500) String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminBulkActionResult(UUID id, String outcome, String error) {
    }
}
