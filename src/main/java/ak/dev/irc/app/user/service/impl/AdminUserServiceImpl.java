package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.UserMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.security.session.SessionDenylist;
import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import ak.dev.irc.app.settings.data.repository.AccountDeletionRequestRepository;
import ak.dev.irc.app.settings.data.repository.DeletedAccountRepository;
import ak.dev.irc.app.settings.data.repository.ExportJobRepository;
import ak.dev.irc.app.settings.data.service.AccountLifecycleService;
import ak.dev.irc.app.settings.safety.enums.ReportTargetType;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.settings.safety.repository.UserStrikeRepository;
import ak.dev.irc.app.settings.safety.service.StrikeService;
import ak.dev.irc.app.settings.storage.StorageUsageService;
import ak.dev.irc.app.user.dto.AdminUserDtos.AcceptInviteRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkActionRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkActionResult;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkCreateRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminBulkRowResult;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminCreateUserRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminDeletionState;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminEditUserRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminExportJobRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminInviteRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminInviteResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminLoginEventRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminModerationSummary;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPasswordResetRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPasswordResetResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminPiiResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminReportRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminSessionRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminSettingsAuditRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminStorage;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminStrikeRequest;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminStrikeRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserAnalyticsResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserDataResponse;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserDetail;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserRow;
import ak.dev.irc.app.user.dto.AdminUserDtos.AdminUserStats;
import ak.dev.irc.app.user.dto.AdminUserDtos.DayCount;
import ak.dev.irc.app.user.dto.request.AdminChangeRoleRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.RefreshToken;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserInvite;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserInviteRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.AdminUserService;
import ak.dev.irc.app.user.service.NotificationService;
import ak.dev.irc.app.user.service.UserProvisioningService;
import ak.dev.irc.app.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of the full admin user-administration surface.
 *
 * <p>Design rules from the docs, enforced here:
 * <ul>
 *   <li><b>One provisioning path</b> — admin-create reuses the extracted
 *       {@link UserProvisioningService} so accounts are structurally
 *       identical to self-signups (admin default role is USER, the
 *       documented intent, not the register path's SCHOLAR).</li>
 *   <li><b>One deletion state machine</b> — admin delete/restore/purge all
 *       delegate to {@link AccountLifecycleService}.</li>
 *   <li><b>Self-protection</b> — an admin cannot disable/lock/delete/demote
 *       themselves; demoting the last ADMIN is rejected.</li>
 *   <li><b>Every mutation writes an {@code ADMIN_*} business audit row</b>
 *       via {@link AdminAuditor}; takeover-relevant actions also notify the
 *       user.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserServiceImpl implements AdminUserService {

    private static final String RESOURCE = "User";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private final UserRepository userRepository;
    private final UserMapper     userMapper;
    private final UserProvisioningService provisioningService;
    private final UserStatsService userStatsService;
    private final StorageUsageService storageUsageService;
    private final AccountLifecycleService accountLifecycleService;
    private final AccountDeletionRequestRepository deletionRepo;
    private final DeletedAccountRepository tombstoneRepo;
    private final ExportJobRepository exportJobRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionDenylist sessionDenylist;
    private final JwtTokenProvider jwtTokenProvider;
    private final ak.dev.irc.app.security.twofa.service.TwoFactorService twoFactorService;
    private final ak.dev.irc.app.security.twofa.service.RecoveryCodeService recoveryCodeService;
    private final ak.dev.irc.app.security.login.service.LoginEventService loginEventService;
    private final ak.dev.irc.app.settings.audit.service.SettingsAuditService settingsAuditService;
    private final StrikeService strikeService;
    private final UserStrikeRepository strikeRepository;
    private final ReportRepository reportRepository;
    private final NotificationService notificationService;
    private final ak.dev.irc.app.email.EmailService emailService;
    private final UserInviteRepository inviteRepository;
    private final ak.dev.irc.app.user.repository.CloseFriendsRepository closeFriendsRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final AdminAuditor adminAuditor;

    @Value("${irc.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    // ═════════════════════════════════════════════════════════════════════
    //  READS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserRow> directory(String q, Role role, String status, Boolean verified,
                                        LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Pageable clamped = PageRequest.of(
                Math.max(0, pageable.getPageNumber()), Pages.clamp(pageable.getPageSize()));

        if (q != null && !q.isBlank()) {
            // Ranked FTS ids → hydrate → in-memory filters (mirrors the
            // platform's user-search path; FTS already excludes deleted rows).
            List<UUID> ids = userRepository.searchUsersFts(q.trim(), 200).stream()
                    .map(r -> (UUID) r[0])
                    .toList();
            List<User> users = userRepository.findAllById(ids).stream()
                    .filter(u -> role == null || u.getRole() == role)
                    .filter(u -> matchesStatus(u, status))
                    .filter(u -> verified == null || (u.getEmailVerifiedAt() != null) == verified)
                    .filter(u -> from == null || !u.getCreatedAt().isBefore(from))
                    .filter(u -> to == null || !u.getCreatedAt().isAfter(to))
                    .sorted((a, b) -> Integer.compare(ids.indexOf(a.getId()), ids.indexOf(b.getId())))
                    .toList();
            int start = (int) Math.min((long) clamped.getPageNumber() * clamped.getPageSize(), users.size());
            int end = Math.min(start + clamped.getPageSize(), users.size());
            List<User> slice = users.subList(start, end);
            Map<UUID, String> avatars = avatarsFor(slice);
            return new PageImpl<>(
                    slice.stream().map(u -> toRow(u, avatars)).toList(),
                    clamped, users.size());
        }

        Boolean enabled = null;
        Boolean deleted = null;
        if (status != null && !status.isBlank()) {
            switch (status.trim().toUpperCase()) {
                case "ACTIVE"   -> { enabled = Boolean.TRUE;  deleted = Boolean.FALSE; }
                case "DISABLED" -> { enabled = Boolean.FALSE; deleted = Boolean.FALSE; }
                case "DELETED"  -> deleted = Boolean.TRUE;
                default -> throw new BadRequestException(
                        UserMessages.INVALID_STATUS_FILTER_MSG,
                        UserMessages.INVALID_STATUS_FILTER);
            }
        }
        Page<User> page = userRepository.adminDirectory(role, enabled, deleted, verified, from, to, clamped);
        Map<UUID, String> avatars = avatarsFor(page.getContent());
        return page.map(u -> toRow(u, avatars));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        User user = requireUser(userId);
        UserProfile profile = userRepository.findActiveWithProfileByIdIn(List.of(userId)).stream()
                .findFirst().map(User::getProfile).orElseGet(user::getProfile);

        AdminUserStats stats = null;
        try {
            var s = userStatsService.statsFor(userId);
            stats = new AdminUserStats(s.postCount(), s.reelCount(), s.researchCount(),
                    s.questionCount(), s.followerCount(), s.followingCount());
        } catch (Exception e) {
            log.debug("stats unavailable for {}: {}", userId, e.getMessage());
        }

        AdminStorage storage = null;
        try {
            var u = storageUsageService.usage(userId);
            storage = new AdminStorage(u.totalBytes(), u.byType());
        } catch (Exception e) {
            log.debug("storage usage unavailable for {}: {}", userId, e.getMessage());
        }

        Map<UUID, String> avatars = avatarsFor(List.of(user));
        return new AdminUserDetail(
                toRow(user, avatars),
                user.getFname(), user.getLname(),
                profile != null ? profile.getProfileBio() : null,
                profile != null ? profile.getLocation() : null,
                profile != null ? profile.getAcademicTitle() : null,
                profile != null ? profile.getInstitutionName() : null,
                profile != null ? profile.getWebsiteUrl() : null,
                user.getOrcidId(),
                user.getPreferredLanguage() != null ? user.getPreferredLanguage().name() : null,
                user.getTimezone(),
                stats, storage,
                deletionState(userId),
                user.getLastAction() != null ? user.getLastAction().name() : null,
                user.getActionNote(),
                user.getUpdatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPiiResponse pii(UUID userId) {
        User user = requireUser(userId);
        adminAuditor.record(AuditOperation.READ, RESOURCE, userId, "ADMIN_PII_REVEAL");
        return new AdminPiiResponse(user.getEmail(), user.getEmailVerifiedAt(),
                user.getPhoneE164(), user.getPhoneVerifiedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminSessionRow> sessions(UUID userId) {
        requireUser(userId);
        return refreshTokenRepository.findActiveSessions(userId, LocalDateTime.now()).stream()
                .map(t -> new AdminSessionRow(t.getSid(), t.getDeviceName(), t.getPlatform(),
                        t.getIpAddress(), t.getLastSeenAt(), t.getCreatedAt(), t.getExpiresAt(),
                        t.isTrustedNow(), t.isRevoked(), t.getRevokedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminLoginEventRow> loginEvents(UUID userId, Pageable pageable) {
        requireUser(userId);
        return loginEventService.history(userId, clamp(pageable))
                .map(e -> new AdminLoginEventRow(e.getIp(), e.getUserAgent(),
                        e.getMethod(), e.getOutcome(), e.getTs()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminSettingsAuditRow> settingsAudit(UUID userId, Pageable pageable) {
        requireUser(userId);
        return settingsAuditService.history(userId, clamp(pageable))
                .map(a -> new AdminSettingsAuditRow(a.getSettingKey(), a.getOldValue(),
                        a.getNewValue(), a.getIp(), a.getCreatedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminModerationSummary moderation(UUID userId) {
        requireUser(userId);
        List<AdminStrikeRow> strikes = strikeRepository.findByUserIdOrderByIssuedAtDesc(userId).stream()
                .map(AdminUserServiceImpl::toStrikeRow)
                .toList();
        long active = strikeRepository.countActive(userId, LocalDateTime.now());
        List<AdminReportRow> against = reportRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        ReportTargetType.USER, userId, PageRequest.of(0, 50))
                .map(AdminUserServiceImpl::toReportRow).getContent();
        List<AdminReportRow> filed = reportRepository
                .findByReporterIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .map(AdminUserServiceImpl::toReportRow).getContent();
        return new AdminModerationSummary(active, strikes, against, filed);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDataResponse data(UUID userId) {
        List<AdminExportJobRow> jobs = exportJobRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(j -> new AdminExportJobRow(j.getId(),
                        j.getStatus() != null ? j.getStatus().name() : null,
                        j.getSizeBytes(), j.getCreatedAt(), j.getReadyAt(), j.getExpiresAt()))
                .toList();
        return new AdminUserDataResponse(jobs, deletionState(userId),
                tombstoneRepo.existsById(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserAnalyticsResponse analytics(int windowDays) {
        int days = Math.max(1, Math.min(windowDays, 365));
        long total = userRepository.countByDeletedAtIsNull();
        LocalDateTime midnight = LocalDate.now().atStartOfDay();
        long today = userRepository.countByCreatedAtGreaterThanEqualAndDeletedAtIsNull(midnight);
        long last7 = userRepository.countByCreatedAtGreaterThanEqualAndDeletedAtIsNull(
                midnight.minusDays(7));

        Map<String, Long> roles = new LinkedHashMap<>();
        for (Object[] row : userRepository.countGroupedByRole()) {
            roles.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        double emailPct = pct(userRepository.countByEmailVerifiedAtIsNotNullAndDeletedAtIsNull(), total);
        double phonePct = pct(userRepository.countByPhoneVerifiedAtIsNotNullAndDeletedAtIsNull(), total);
        double twoFaPct = pct(userRepository.countByTwoFactorEnabledTrueAndDeletedAtIsNull(), total);

        Map<String, Long> deletionPipeline = new LinkedHashMap<>();
        for (DeletionStatus s : DeletionStatus.values()) {
            deletionPipeline.put(s.name(), deletionRepo.countByStatus(s));
        }

        List<DayCount> signups = userRepository.signupsPerDay(midnight.minusDays(days)).stream()
                .map(r -> new DayCount(String.valueOf(r[0]), ((Number) r[1]).longValue()))
                .toList();

        // Close-friends adoption block (users-roles.md §6.1) — aggregate-only,
        // no individual lists are ever surfaced here.
        Map<String, Object> closeFriends = new LinkedHashMap<>();
        try {
            long owners = closeFriendsRepository.countDistinctOwners();
            closeFriends.put("usersWithList", owners);
            closeFriends.put("adoptionPct", pct(owners, total));
            closeFriends.put("totalEdges", closeFriendsRepository.countEdges());
            Map<String, Long> histogram = new LinkedHashMap<>();
            for (Object[] row : closeFriendsRepository.sizeHistogram()) {
                histogram.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
            closeFriends.put("listSizeHistogram", histogram);
        } catch (Exception e) {
            closeFriends.put("error", "close-friends aggregates unavailable: " + e.getMessage());
        }

        // Sessions-per-user distribution (users-roles.md §6).
        Map<String, Object> sessions = new LinkedHashMap<>();
        try {
            List<Object[]> rows = refreshTokenRepository.sessionsPerUserPercentiles();
            if (!rows.isEmpty() && rows.get(0)[4] != null
                    && ((Number) rows.get(0)[4]).longValue() > 0) {
                Object[] r = rows.get(0);
                sessions.put("p50", ((Number) r[0]).doubleValue());
                sessions.put("p95", ((Number) r[1]).doubleValue());
                sessions.put("avg", Math.round(((Number) r[2]).doubleValue() * 100) / 100.0);
                sessions.put("max", ((Number) r[3]).longValue());
                sessions.put("usersWithLiveSessions", ((Number) r[4]).longValue());
            } else {
                sessions.put("usersWithLiveSessions", 0L);
            }
        } catch (Exception e) {
            sessions.put("error", "session distribution unavailable: " + e.getMessage());
        }

        return new AdminUserAnalyticsResponse(total, today, last7, roles,
                emailPct, phonePct, twoFaPct, deletionPipeline, signups, closeFriends, sessions);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ROLE / STATE
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public UserResponse changeRole(UUID targetUserId, AdminChangeRoleRequest req) {
        if (req == null || req.role() == null)
            throw new BadRequestException(UserMessages.ROLE_REQUIRED_MSG, UserMessages.INVALID_INPUT);
        requireNotSelf(targetUserId, "change your own role");

        User target = requireUser(targetUserId);
        Role previous = target.getRole();
        if (previous == req.role()) {
            log.info("Admin role change for user [{}] is a no-op (already {})", target.getId(), previous);
            return userMapper.toResponse(target);
        }
        if (previous == Role.ADMIN
                && userRepository.countByRoleAndDeletedAtIsNull(Role.ADMIN) <= 1) {
            throw new ConflictException(UserMessages.LAST_ADMIN_MSG, UserMessages.LAST_ADMIN);
        }

        target.setRole(req.role());
        String note = "Role %s → %s%s".formatted(
                previous, req.role(),
                req.reason() != null && !req.reason().isBlank() ? " (" + req.reason() + ")" : "");
        target.audit(AuditAction.UPDATE, note);
        userRepository.save(target);

        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, targetUserId,
                "ADMIN_USER_ROLE_CHANGE", note);
        log.info("Admin changed role for user [{}]: {} → {}", target.getId(), previous, req.role());
        return userMapper.toResponse(target);
    }

    @Override
    public void disable(UUID userId, String reason) {
        requireNotSelf(userId, "disable yourself");
        User user = requireUser(userId);
        if (!user.isEnabled() && user.getDeletedAt() == null) {
            return; // idempotent
        }
        user.setEnabled(false);
        user.audit(AuditAction.UPDATE, note("Account disabled by admin", reason));
        userRepository.save(user);
        revokeAllSessionsInternal(userId);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_USER_DISABLE", reason);
        notifyQuietly(userId, UserMessages.NOTIF_ACCOUNT_DISABLED_TITLE,
                UserMessages.NOTIF_ACCOUNT_DISABLED_BODY);
    }

    @Override
    public void enable(UUID userId) {
        User user = requireUser(userId);
        user.setEnabled(true);
        user.audit(AuditAction.UPDATE, "Account re-enabled by admin");
        userRepository.save(user);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_USER_ENABLE");
        notifyQuietly(userId, UserMessages.NOTIF_ACCOUNT_ENABLED_TITLE,
                UserMessages.NOTIF_ACCOUNT_ENABLED_BODY);
    }

    @Override
    public void lock(UUID userId, String reason) {
        requireNotSelf(userId, "lock yourself");
        User user = requireUser(userId);
        user.setAccountNonLocked(false);
        user.audit(AuditAction.UPDATE, note("Account locked by admin", reason));
        userRepository.save(user);
        revokeAllSessionsInternal(userId);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_USER_LOCK", reason);
    }

    @Override
    public void unlock(UUID userId) {
        User user = requireUser(userId);
        user.setAccountNonLocked(true);
        user.audit(AuditAction.UPDATE, "Account unlocked by admin");
        userRepository.save(user);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_USER_UNLOCK");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SESSIONS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void revokeSession(UUID userId, UUID sid) {
        requireUser(userId);
        RefreshToken token = refreshTokenRepository.findBySid(sid)
                .orElseThrow(() -> new ResourceNotFoundException("Session", "sid", sid));
        if (token.getUser() == null || !Objects.equals(token.getUser().getId(), userId)) {
            throw new ResourceNotFoundException("Session", "sid", sid);
        }
        refreshTokenRepository.revokeBySid(sid, LocalDateTime.now());
        sessionDenylist.deny(sid, Duration.ofMillis(jwtTokenProvider.getAccessTokenExpirationMs()));
        adminAuditor.record(AuditOperation.DELETE, RESOURCE, userId,
                "ADMIN_SESSION_REVOKE", "sid=" + sid);
    }

    @Override
    public int revokeAllSessions(UUID userId) {
        requireUser(userId);
        int count = revokeAllSessionsInternal(userId);
        adminAuditor.record(AuditOperation.DELETE, RESOURCE, userId,
                "ADMIN_SESSIONS_REVOKE_ALL", "sessions=" + count);
        return count;
    }

    private int revokeAllSessionsInternal(UUID userId) {
        List<RefreshToken> live = refreshTokenRepository.findActiveSessions(userId, LocalDateTime.now());
        Duration accessTtl = Duration.ofMillis(jwtTokenProvider.getAccessTokenExpirationMs());
        for (RefreshToken t : live) {
            if (t.getSid() != null) {
                sessionDenylist.deny(t.getSid(), accessTtl);
            }
        }
        refreshTokenRepository.revokeAllForUser(userId);
        return live.size();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CREDENTIALS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public AdminPasswordResetResponse resetPassword(UUID userId, AdminPasswordResetRequest request) {
        requireNotSelf(userId, "admin-reset your own password (use change-password)");
        User user = requireUser(userId);

        String temp = request != null && request.temporaryPassword() != null
                ? request.temporaryPassword()
                : generateTempPassword();
        user.setPassword(passwordEncoder.encode(temp));
        user.audit(AuditAction.PASSWORD_CHANGE, "Password reset by admin");
        userRepository.save(user);

        int revoked = revokeAllSessionsInternal(userId);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId,
                "ADMIN_PASSWORD_RESET", "sessions revoked=" + revoked);
        notifyQuietly(userId, UserMessages.NOTIF_PASSWORD_RESET_TITLE,
                UserMessages.NOTIF_PASSWORD_RESET_BODY);
        return new AdminPasswordResetResponse(temp, revoked);
    }

    @Override
    public void reset2fa(UUID userId, String reason) {
        requireNotSelf(userId, "admin-reset your own 2FA (use security settings)");
        requireUser(userId);
        twoFactorService.disable(userId);
        recoveryCodeService.clear(userId);
        int revoked = revokeAllSessionsInternal(userId);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId,
                "ADMIN_2FA_RESET", note("sessions revoked=" + revoked, reason));
        notifyQuietly(userId, UserMessages.NOTIF_TWO_FA_RESET_TITLE,
                UserMessages.NOTIF_TWO_FA_RESET_BODY);
    }

    @Override
    public void markEmailVerified(UUID userId) {
        User user = requireUser(userId);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
            user.audit(AuditAction.EMAIL_VERIFY, "Email marked verified by admin");
            userRepository.save(user);
        }
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_EMAIL_VERIFY");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  PROVISIONING
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public AdminUserDetail create(AdminCreateUserRequest request) {
        boolean sendInvite = Boolean.TRUE.equals(request.sendInvite());
        if (request.temporaryPassword() == null && !sendInvite) {
            throw new BadRequestException(
                    UserMessages.PASSWORD_OR_INVITE_REQUIRED_MSG,
                    UserMessages.PASSWORD_OR_INVITE_REQUIRED);
        }
        // The admin form's documented default is USER — never inherit the
        // register path's SCHOLAR fallback.
        Role role = request.role() != null ? request.role() : Role.USER;

        User user = provisioningService.provision(new UserProvisioningService.ProvisionCommand(
                request.fname(), request.lname(), request.username(), request.email(),
                request.temporaryPassword(), role, true,
                Boolean.TRUE.equals(request.markEmailVerified()),
                "Created by admin"));

        adminAuditor.record(AuditOperation.CREATE, RESOURCE, user.getId(),
                "ADMIN_USER_CREATE", "role=" + role + (sendInvite ? ", invite sent" : ""));

        if (sendInvite) {
            UserInvite invite = saveInvite(user.getEmail(), role);
            mailInvite(invite, UserMessages.NOTIF_INVITE_SUBJECT,
                    UserMessages.NOTIF_INVITE_ADMIN_CREATED_INTRO);
        }
        return detail(user.getId());
    }

    @Override
    public List<AdminBulkRowResult> bulkCreate(AdminBulkCreateRequest request) {
        List<AdminBulkRowResult> results = new ArrayList<>();
        int i = 0;
        for (AdminCreateUserRequest row : request.rows()) {
            int index = i++;
            try {
                AdminCreateUserRequest effective = row.temporaryPassword() == null
                        && !Boolean.TRUE.equals(row.sendInvite())
                        ? new AdminCreateUserRequest(row.fname(), row.lname(), row.username(),
                                row.email(), row.role(), null, Boolean.TRUE, row.markEmailVerified())
                        : row;
                AdminUserDetail created = create(effective);
                results.add(new AdminBulkRowResult(index, row.username(), row.email(),
                        "created", created.user().id(), null));
            } catch (DuplicateResourceException dup) {
                results.add(new AdminBulkRowResult(index, row.username(), row.email(),
                        "skipped:duplicate", null, dup.getMessage()));
            } catch (Exception exd) {
                results.add(new AdminBulkRowResult(index, row.username(), row.email(),
                        "error", null, exd.getMessage()));
            }
        }
        long created = results.stream().filter(r -> "created".equals(r.outcome())).count();
        adminAuditor.record(AuditOperation.CREATE, RESOURCE, null,
                "ADMIN_USER_BULK_CREATE",
                "rows=" + results.size() + ", created=" + created);
        return results;
    }

    @Override
    public AdminInviteResponse invite(AdminInviteRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException(RESOURCE, "email", request.email());
        }
        Role role = request.role() != null ? request.role() : Role.USER;
        UserInvite invite = saveInvite(request.email(), role);
        mailInvite(invite, UserMessages.NOTIF_INVITE_SUBJECT,
                UserMessages.NOTIF_INVITE_JOIN_INTRO);
        adminAuditor.record(AuditOperation.CREATE, "UserInvite", invite.getId(),
                "ADMIN_USER_INVITE", request.email() + " as " + role);
        return toInviteResponse(invite);
    }

    @Override
    public AdminInviteResponse resendInvite(UUID inviteId) {
        UserInvite invite = inviteRepository.findById(inviteId)
                .orElseThrow(() -> new ResourceNotFoundException("UserInvite", "id", inviteId));
        if (invite.getUsedAt() != null) {
            throw new ConflictException(UserMessages.INVITE_USED_MSG, UserMessages.INVITE_USED);
        }
        invite.setRevokedAt(null);
        invite.setExpiresAt(LocalDateTime.now().plusDays(7));
        inviteRepository.save(invite);
        mailInvite(invite, UserMessages.NOTIF_INVITE_RESEND_SUBJECT,
                UserMessages.NOTIF_INVITE_JOIN_INTRO);
        adminAuditor.record(AuditOperation.UPDATE, "UserInvite", inviteId, "ADMIN_INVITE_RESEND");
        return toInviteResponse(invite);
    }

    @Override
    public void revokeInvite(UUID inviteId) {
        UserInvite invite = inviteRepository.findById(inviteId)
                .orElseThrow(() -> new ResourceNotFoundException("UserInvite", "id", inviteId));
        invite.setRevokedAt(LocalDateTime.now());
        inviteRepository.save(invite);
        adminAuditor.record(AuditOperation.DELETE, "UserInvite", inviteId, "ADMIN_INVITE_REVOKE");
    }

    @Override
    public UserResponse acceptInvite(AcceptInviteRequest request) {
        UserInvite invite = inviteRepository.findByToken(request.token())
                .filter(UserInvite::isUsable)
                .orElseThrow(() -> new BadRequestException(
                        UserMessages.INVITE_INVALID_MSG, UserMessages.INVITE_INVALID));

        User user = userRepository.findByEmailAndDeletedAtIsNull(invite.getEmail()).orElse(null);
        if (user != null) {
            // Admin-created account waiting for its set-password link.
            user.setPassword(passwordEncoder.encode(request.password()));
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(LocalDateTime.now());
            }
            user.audit(AuditAction.PASSWORD_CHANGE, "Password set via invite");
            userRepository.save(user);
        } else {
            user = provisioningService.provision(new UserProvisioningService.ProvisionCommand(
                    request.fname(), request.lname(), request.username(), invite.getEmail(),
                    request.password(), invite.getRole(), true, true,
                    "Account created via admin invite"));
        }
        invite.setUsedAt(LocalDateTime.now());
        inviteRepository.save(invite);
        return userMapper.toResponse(user);
    }

    @Override
    public UserResponse edit(UUID userId, AdminEditUserRequest request) {
        User user = requireUser(userId);
        List<String> changes = new ArrayList<>();

        if (request.username() != null && !request.username().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.username())) {
                throw new DuplicateResourceException(RESOURCE, "username", request.username());
            }
            changes.add("username " + user.getUsername() + " → " + request.username());
            user.setUsername(request.username());
        }
        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            if (userRepository.existsByEmail(request.email())) {
                throw new DuplicateResourceException(RESOURCE, "email", request.email());
            }
            changes.add("email " + user.getEmail() + " → " + request.email());
            user.setEmail(request.email());
            user.setEmailVerifiedAt(null);   // recovery vector changed — re-verify
        }
        if (request.fname() != null && !request.fname().equals(user.getFname())) {
            changes.add("fname");
            user.setFname(request.fname());
        }
        if (request.lname() != null && !request.lname().equals(user.getLname())) {
            changes.add("lname");
            user.setLname(request.lname());
        }
        if (changes.isEmpty()) {
            return userMapper.toResponse(user);
        }
        user.audit(AuditAction.UPDATE, "Identity edited by admin: " + String.join(", ", changes));
        userRepository.save(user);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId,
                "ADMIN_USER_EDIT", String.join(", ", changes));
        return userMapper.toResponse(user);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIFECYCLE / MODERATION / BULK
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public AdminUserDataResponse requestDeletion(UUID userId, String reason) {
        requireNotSelf(userId, "delete yourself via the admin surface");
        requireUser(userId);
        accountLifecycleService.requestDeletion(userId);
        adminAuditor.record(AuditOperation.DELETE, RESOURCE, userId,
                "ADMIN_ACCOUNT_DELETE_REQUEST", reason);
        return data(userId);
    }

    @Override
    public AdminUserDataResponse cancelDeletion(UUID userId) {
        accountLifecycleService.cancelDeletion(userId);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId, "ADMIN_ACCOUNT_DELETE_CANCEL");
        return data(userId);
    }

    @Override
    public AdminUserDataResponse purgeNow(UUID userId) {
        requireNotSelf(userId, "purge yourself");
        accountLifecycleService.purgeNow(userId);
        adminAuditor.record(AuditOperation.DELETE, RESOURCE, userId, "ADMIN_PURGE_NOW");
        return data(userId);
    }

    @Override
    public AdminUserDataResponse holdPurge(UUID userId, int days) {
        accountLifecycleService.holdPurge(userId, days);
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, userId,
                "ADMIN_PURGE_HOLD", "+" + days + "d");
        return data(userId);
    }

    @Override
    public AdminStrikeRow issueStrike(UUID userId, AdminStrikeRequest request) {
        requireNotSelf(userId, "strike yourself");
        requireUser(userId);
        var strike = strikeService.issueStrike(userId, request.reportId(), request.reason());
        adminAuditor.record(AuditOperation.CREATE, RESOURCE, userId,
                "ADMIN_STRIKE_ISSUE", request.reason());
        notifyQuietly(userId, UserMessages.NOTIF_STRIKE_TITLE,
                UserMessages.NOTIF_STRIKE_BODY.formatted(request.reason()));
        return toStrikeRow(strike);
    }

    @Override
    public List<AdminBulkActionResult> bulkAction(AdminBulkActionRequest request) {
        String action = request.action().trim().toUpperCase();
        List<AdminBulkActionResult> results = new ArrayList<>();
        for (UUID id : request.ids()) {
            try {
                switch (action) {
                    case "DISABLE" -> disable(id, request.reason());
                    case "ENABLE" -> enable(id);
                    case "LOCK" -> lock(id, request.reason());
                    case "UNLOCK" -> unlock(id);
                    case "REQUEST_DELETION" -> requestDeletion(id, request.reason());
                    case "CHANGE_ROLE" -> {
                        if (request.role() == null) {
                            throw new BadRequestException(UserMessages.ROLE_REQUIRED_FOR_CHANGE_ROLE_MSG,
                                    UserMessages.INVALID_INPUT);
                        }
                        changeRole(id, new AdminChangeRoleRequest(request.role(), request.reason()));
                    }
                    default -> throw new BadRequestException(
                            UserMessages.INVALID_BULK_ACTION_MSG,
                            UserMessages.INVALID_BULK_ACTION);
                }
                results.add(new AdminBulkActionResult(id, "ok", null));
            } catch (Exception exd) {
                results.add(new AdminBulkActionResult(id, "error", exd.getMessage()));
            }
        }
        long ok = results.stream().filter(r -> "ok".equals(r.outcome())).count();
        adminAuditor.record(AuditOperation.UPDATE, RESOURCE, null, "ADMIN_BULK_ACTION",
                action + " on " + request.ids().size() + " users, ok=" + ok);
        return results;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ═════════════════════════════════════════════════════════════════════

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, "id", userId));
    }

    private void requireNotSelf(UUID targetUserId, String what) {
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        if (adminId != null && adminId.equals(targetUserId)) {
            throw new ForbiddenException(UserMessages.SELF_ACTION_FORBIDDEN_MSG.formatted(what),
                    UserMessages.SELF_ACTION_FORBIDDEN);
        }
    }

    private Map<UUID, String> avatarsFor(List<User> users) {
        List<UUID> ids = users.stream().map(User::getId).toList();
        if (ids.isEmpty()) return Map.of();
        try {
            return userRepository.findActiveWithProfileByIdIn(ids).stream()
                    .filter(u -> u.getProfile() != null && u.getProfile().getAvatarUrl() != null)
                    .collect(Collectors.toMap(User::getId, u -> u.getProfile().getAvatarUrl(),
                            (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private AdminUserRow toRow(User u, Map<UUID, String> avatars) {
        String status;
        if (u.getDeletedAt() != null) status = "DELETED";
        else if (!u.isAccountNonLocked()) status = "LOCKED";
        else if (!u.isEnabled()) status = "DISABLED";
        else status = "ACTIVE";
        return new AdminUserRow(
                u.getId(), u.getUsername(), u.getFullName(), maskEmail(u.getEmail()),
                avatars.get(u.getId()), u.getRole(), userMapper.resolveBadges(u),
                u.getEmailVerifiedAt() != null, u.getPhoneVerifiedAt() != null,
                u.isTwoFactorEnabled(),
                u.isEnabled(), !u.isAccountNonLocked(), status,
                u.getCreatedAt(), u.getLastLoginAt(), u.getDeletedAt());
    }

    private AdminDeletionState deletionState(UUID userId) {
        return deletionRepo.findFirstByUserIdOrderByRequestedAtDesc(userId)
                .map(this::toDeletionState)
                .orElse(null);
    }

    private AdminDeletionState toDeletionState(AccountDeletionRequest r) {
        return new AdminDeletionState(
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getRequestedAt(), r.getPurgeAfter(), r.getResolvedAt());
    }

    private static AdminStrikeRow toStrikeRow(ak.dev.irc.app.settings.safety.entity.UserStrike s) {
        return new AdminStrikeRow(s.getId(), s.getReportId(), s.getReason(),
                s.isActive(), s.getIssuedAt(), s.getExpiresAt());
    }

    private static AdminReportRow toReportRow(ak.dev.irc.app.settings.safety.entity.Report r) {
        return new AdminReportRow(r.getId(),
                r.getTargetType() != null ? r.getTargetType().name() : null,
                r.getTargetId(),
                r.getReason() != null ? r.getReason().name() : null,
                r.getState() != null ? r.getState().name() : null,
                r.getResolution() != null ? r.getResolution().name() : null,
                r.getCreatedAt());
    }

    private boolean matchesStatus(User u, String status) {
        if (status == null || status.isBlank()) return true;
        return switch (status.trim().toUpperCase()) {
            case "ACTIVE"   -> u.getDeletedAt() == null && u.isEnabled();
            case "DISABLED" -> u.getDeletedAt() == null && !u.isEnabled();
            case "DELETED"  -> u.getDeletedAt() != null;
            case "LOCKED"   -> !u.isAccountNonLocked();
            default -> throw new BadRequestException(
                    UserMessages.INVALID_STATUS_FILTER_WITH_LOCKED_MSG,
                    UserMessages.INVALID_STATUS_FILTER);
        };
    }

    private UserInvite saveInvite(String email, Role role) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        return inviteRepository.save(UserInvite.builder()
                .email(email.trim().toLowerCase())
                .role(role)
                .token(token)
                .invitedBy(adminId)
                .build());
    }

    private void mailInvite(UserInvite invite, String subject, String intro) {
        try {
            String link = frontendBaseUrl + "/invite?token=" + invite.getToken();
            String plain = intro + link + "\nThe link expires on " + invite.getExpiresAt() + ".";
            String html = "<p>" + intro + "<a href=\"" + link + "\">" + link + "</a></p>"
                    + "<p>The link expires on " + invite.getExpiresAt() + ".</p>";
            emailService.sendAsync(invite.getEmail(), subject, plain, html);
        } catch (Exception e) {
            log.warn("Invite email to {} failed: {}", invite.getEmail(), e.getMessage());
        }
    }

    private static AdminInviteResponse toInviteResponse(UserInvite i) {
        return new AdminInviteResponse(i.getId(), i.getEmail(), i.getRole(),
                i.getExpiresAt(), i.getUsedAt(), i.getCreatedAt());
    }

    private void notifyQuietly(UUID userId, String title, String body) {
        try {
            notificationService.sendSystemNotification(userId, title, body);
        } catch (Exception e) {
            log.debug("System notification to {} skipped: {}", userId, e.getMessage());
        }
    }

    private static String generateTempPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at - 1);
    }

    private static String note(String base, String reason) {
        return reason == null || reason.isBlank() ? base : base + " (" + reason + ")";
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static Pageable clamp(Pageable pageable) {
        return PageRequest.of(Math.max(0, pageable.getPageNumber()),
                Pages.clamp(pageable.getPageSize()));
    }
}
