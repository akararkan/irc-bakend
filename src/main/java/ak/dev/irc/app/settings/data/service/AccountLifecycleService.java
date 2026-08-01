package ak.dev.irc.app.settings.data.service;

import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
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

    @Transactional
    public AccountDeletionRequest requestDeletion(UUID userId) {
        deletionRepo.findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .ifPresent(r -> {
                    throw new ConflictException("Deletion already requested.", "DELETION_PENDING");
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
                .orElseThrow(() -> new ResourceNotFoundException("No pending deletion to cancel."));
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
        List<AccountDeletionRequest> due = deletionRepo.findByStatusAndPurgeAfterBefore(
                DeletionStatus.PENDING_DELETION, LocalDateTime.now());
        if (due.isEmpty()) return;
        log.info("[ACCOUNT-DELETE] purging {} expired deletion(s)", due.size());
        for (AccountDeletionRequest req : due) {
            try {
                anonymizeAndPurge(req);
            } catch (Exception ex) {
                log.error("[ACCOUNT-DELETE] purge failed for user {}: {}",
                        req.getUserId(), ex.getMessage(), ex);
            }
        }
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
        // Tombstone prevents id reuse; retained intentionally (§16).
        tombstoneRepo.save(DeletedAccount.builder()
                .id(userId).deletedAt(LocalDateTime.now()).build());
        req.setStatus(DeletionStatus.PURGED);
        req.setResolvedAt(LocalDateTime.now());
        deletionRepo.save(req);
        log.info("[ACCOUNT-DELETE] user {} anonymized + purged", userId);
    }

    @Transactional(readOnly = true)
    public AccountDeletionRequest currentPending(UUID userId) {
        return deletionRepo.findFirstByUserIdAndStatus(userId, DeletionStatus.PENDING_DELETION)
                .orElse(null);
    }
}
