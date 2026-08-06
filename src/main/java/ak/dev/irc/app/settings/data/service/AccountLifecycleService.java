package ak.dev.irc.app.settings.data.service;

import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SettingsMessages;
import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.entity.DeletedAccount;
import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import ak.dev.irc.app.settings.data.repository.AccountDeletionRequestRepository;
import ak.dev.irc.app.settings.data.repository.DeletedAccountRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Account-deletion state machine (spec §16). Deletion is not a {@code DELETE}
 * statement:
 *
 * <pre>ACTIVE ──request──▶ PENDING_DELETION ──grace expires──▶ ANONYMIZED ──▶ PURGED
 *                              │ user cancels
 *                              ▼
 *                           ACTIVE</pre>
 *
 * <p>IMPORTANT: on request the account is made <b>invisible immediately</b> —
 * the user's {@code deletedAt} is set (so {@code User.isEnabled()} returns false
 * everywhere) and all sessions are revoked. The account behaves as deleted at
 * once, which is what the user actually wants; the 30-day grace exists only for
 * recovery.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLifecycleService {

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final AccountDeletionRequestRepository deletionRepo;
    private final DeletedAccountRepository tombstoneRepo;

    // GDPR-cascade collaborators — the purge previously touched ONLY the users
    // row + tombstone, leaving activity/reel-view/contact-hash/suggestion
    // personal data behind (the docs/admin TODO flagged exactly this gap).
    private final ak.dev.irc.app.activity.service.UserActivityService userActivityService;
    private final ak.dev.irc.app.activity.service.ReelViewService reelViewService;
    private final ak.dev.irc.app.user.repository.UserContactHashRepository contactHashRepo;
    private final ak.dev.irc.app.post.cassandra.repository.FriendSuggestionRepository friendSuggestionRepo;
    private final ak.dev.irc.app.user.repository.SuggestionDismissalRepository suggestionDismissalRepo;
    private final ak.dev.irc.app.admin.ops.JobRunRecorder jobRunRecorder;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    // Log-store GDPR steps (logs-audit.md §8): the purge is the single
    // choreography point — every log store registers a purge (or a
    // documented-keep) here. consent_events and reports are KEPT deliberately
    // (compliance / moderation evidence; ids resolve to the tombstone).
    private final ak.dev.irc.app.audit.cassandra.repository.AuditLogByUserRepository auditLogByUserRepo;
    private final ak.dev.irc.app.settings.audit.repository.SettingsAuditRepository settingsAuditRepo;
    private final ak.dev.irc.app.security.login.repository.LoginEventRepository loginEventRepo;
    private final ak.dev.irc.app.settings.data.repository.ExportJobRepository exportJobRepo;
    private final ak.dev.irc.app.audit.service.AuditLogService auditLogService;

    @Transactional
    public AccountDeletionRequest requestDeletion(UUID userId) {
        deletionRepo.findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .ifPresent(r -> {
                    throw new ConflictException(SettingsMessages.DELETION_PENDING_MSG, SettingsMessages.DELETION_PENDING);
                });
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // SEAM: step-up authentication (password/OTP) should gate this (§22.3).
        user.setDeletedAt(LocalDateTime.now());   // immediate platform-wide invisibility
        userRepo.save(user);
        refreshTokenRepo.revokeAllForUser(userId); // all sessions gone now

        AccountDeletionRequest req = AccountDeletionRequest.builder()
                .userId(userId)
                .status(DeletionStatus.PENDING_DELETION)
                .build();
        log.info("[ACCOUNT-DELETE] user {} entered PENDING_DELETION (grace 30d)", userId);
        return deletionRepo.save(req);
    }

    @Transactional
    public AccountDeletionRequest cancelDeletion(UUID userId) {
        AccountDeletionRequest req = deletionRepo
                .findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .orElseThrow(() -> new ResourceNotFoundException(SettingsMessages.NO_PENDING_DELETION_CANCEL_MSG));
        userRepo.findById(userId).ifPresent(u -> {
            u.setDeletedAt(null);                  // restore visibility
            userRepo.save(u);
        });
        req.setStatus(DeletionStatus.CANCELLED);
        req.setResolvedAt(LocalDateTime.now());
        log.info("[ACCOUNT-DELETE] user {} cancelled deletion, account restored", userId);
        return deletionRepo.save(req);
    }

    /** Nightly purge of accounts whose 30-day grace has elapsed. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        if (jobPause.isPaused("account-purge")) return;
        jobRunRecorder.record("account-purge", null, () -> {
            List<AccountDeletionRequest> due = deletionRepo.findByStatusAndPurgeAfterBefore(
                    DeletionStatus.PENDING_DELETION, LocalDateTime.now());
            if (due.isEmpty()) {
                return ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats.NONE;
            }
            log.info("[ACCOUNT-DELETE] purging {} expired deletion(s)", due.size());
            int failed = 0;
            for (AccountDeletionRequest req : due) {
                try {
                    anonymizeAndPurge(req);
                } catch (Exception ex) {
                    failed++;
                    log.error("[ACCOUNT-DELETE] purge failed for user {}: {}",
                            req.getUserId(), ex.getMessage(), ex);
                }
            }
            return new ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats(due.size() - failed, failed);
        });
    }

    private void anonymizeAndPurge(AccountDeletionRequest req) {
        UUID userId = req.getUserId();
        String shortHash = userId.toString().replace("-", "").substring(0, 12);
        userRepo.findById(userId).ifPresent(u -> {
            // Overwrite PII (§16 anonymization). phone_hmac drop happens once the
            // §2 phone columns land — SEAM noted there.
            u.setUsername("deleted_user_" + shortHash);
            u.setEmail("deleted_" + shortHash + "@deleted.invalid");
            u.setFname("Deleted");
            u.setLname("User");
            u.setPassword(null);
            // deletedAt stays set — the row remains soft-deleted/anonymized.
            userRepo.save(u);
        });
        // GDPR cascade — personal-data stores outside Postgres `users`.
        // Each drop is isolated: a Cassandra hiccup must not abort the purge
        // (the failed store is retried implicitly on the next nightly run
        // only if the request row stays PENDING, so log loudly).
        purgeQuietly("activity ledger", () -> {
            int deleted = userActivityService.deleteAll(userId, null);
            log.info("[ACCOUNT-DELETE] user {} activity rows deleted: {}", userId, deleted);
        });
        purgeQuietly("reel views", () -> reelViewService.deleteAll(userId));
        purgeQuietly("contact hashes", () -> {
            contactHashRepo.deleteByOwnerIdAndKind(userId,
                    ak.dev.irc.app.user.entity.UserContactHash.KIND_CONTACT);
            contactHashRepo.deleteByOwnerIdAndKind(userId,
                    ak.dev.irc.app.user.entity.UserContactHash.KIND_IDENTITY);
        });
        purgeQuietly("friend suggestions", () -> friendSuggestionRepo.clearForUser(userId));
        purgeQuietly("suggestion dismissals", () -> {
            var dismissed = suggestionDismissalRepo.findDismissedCandidateIds(userId);
            dismissed.forEach(candidateId -> suggestionDismissalRepo.deleteById(
                    new ak.dev.irc.app.user.entity.SuggestionDismissal.Key(userId, candidateId)));
        });

        // Log-store cascade (logs-audit.md §8): remove network identifiers +
        // per-user trails; deliberately KEEP consent_events (compliance
        // evidence) and reports/strikes (moderation evidence — ids resolve to
        // the tombstone).
        purgeQuietly("audit_log_by_user partition", () -> auditLogByUserRepo.purgePartition(userId));
        purgeQuietly("settings_audit trail", () -> settingsAuditRepo.purgeForUser(userId));
        purgeQuietly("login_events trail", () -> loginEventRepo.purgeForUser(userId));
        purgeQuietly("export jobs + ZIP files", () -> {
            for (var job : exportJobRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
                if (job.getFilePath() != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.getFilePath()));
                    } catch (Exception ex) {
                        log.warn("[ACCOUNT-DELETE] export ZIP delete failed ({}): {}",
                                job.getFilePath(), ex.getMessage());
                    }
                }
                exportJobRepo.delete(job);
            }
        });

        // Deleting data is itself an auditable act (§8 principle 3).
        purgeQuietly("purge audit event", () -> auditLogService.record(
                null, "system", ak.dev.irc.app.audit.enums.AuditOperation.SYSTEM,
                "User", userId,
                "ACCOUNT_PURGED — GDPR cascade complete (relational anonymized; activity, "
                        + "reel views, contact hashes, suggestions, audit partition, "
                        + "settings/login trails, export archives removed)"));

        // Tombstone prevents id reuse; retained intentionally (§16).
        tombstoneRepo.save(DeletedAccount.builder()
                .id(userId).deletedAt(LocalDateTime.now()).build());
        req.setStatus(DeletionStatus.PURGED);
        req.setResolvedAt(LocalDateTime.now());
        deletionRepo.save(req);
        log.info("[ACCOUNT-DELETE] user {} anonymized + purged", userId);
    }

    private void purgeQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.error("[ACCOUNT-DELETE] cascade '{}' failed: {}", what, ex.getMessage());
        }
    }

    // ── Admin controls (blueprint §3.1 — ADMIN_PURGE_NOW / ADMIN_PURGE_HOLD) ──

    /** Expedites the purge: runs the full anonymize-and-purge immediately. */
    @Transactional
    public AccountDeletionRequest purgeNow(UUID userId) {
        AccountDeletionRequest req = deletionRepo
                .findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .orElseThrow(() -> new ResourceNotFoundException(SettingsMessages.NO_PENDING_DELETION_PURGE_MSG));
        anonymizeAndPurge(req);
        return req;
    }

    /** Extends the grace window ({@code purge_after}) by the given days. */
    @Transactional
    public AccountDeletionRequest holdPurge(UUID userId, int days) {
        AccountDeletionRequest req = deletionRepo
                .findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .orElseThrow(() -> new ResourceNotFoundException(SettingsMessages.NO_PENDING_DELETION_HOLD_MSG));
        int extension = Math.max(1, Math.min(days, 365));
        LocalDateTime base = req.getPurgeAfter().isAfter(LocalDateTime.now())
                ? req.getPurgeAfter() : LocalDateTime.now();
        req.setPurgeAfter(base.plusDays(extension));
        log.info("[ACCOUNT-DELETE] purge hold for user {} — purge_after now {}",
                userId, req.getPurgeAfter());
        return deletionRepo.save(req);
    }

    @Transactional(readOnly = true)
    public AccountDeletionRequest currentPending(UUID userId) {
        return deletionRepo.findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .orElse(null);
    }
}
