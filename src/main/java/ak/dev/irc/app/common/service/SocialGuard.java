package ak.dev.irc.app.common.service;

import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserRestrictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Single entry-point for the "is this interaction allowed?" question.
 *
 * <p>Centralises the block / restriction policy used by every social
 * write path (post react, comment, reply, repost, mention, …). Doing
 * the check in one place avoids the drift that creeps in when each
 * service rolls its own guard logic.</p>
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>Block</b> — symmetric. If either side has blocked the other,
 *       the write is rejected with HTTP 403 and the actor sees a generic
 *       "interaction not allowed" message (never leak who blocked whom).</li>
 *   <li><b>Restriction</b> — asymmetric and silent. The restricted user
 *       can still write, but the restrictor never gets a notification.
 *       Used by callers on the notification side, not the write side.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SocialGuard {

    private final UserBlockRepository blockRepo;
    private final UserRestrictionRepository restrictionRepo;

    // ── Block: write-path guards ──────────────────────────────────────────

    /**
     * Reject the action if either user has blocked the other. Skips the DB
     * call when actor and target are the same user — self-actions are never
     * blocked by this guard.
     */
    @Transactional(readOnly = true)
    public void requireNotBlockedBetween(UUID actorId, UUID targetId, String errorCode) {
        if (actorId == null || targetId == null || actorId.equals(targetId)) return;
        if (blockRepo.isBlockedBetween(actorId, targetId)) {
            throw new ForbiddenException(
                    "This interaction is not allowed.",
                    errorCode);
        }
    }

    @Transactional(readOnly = true)
    public boolean isBlockedBetween(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        return blockRepo.isBlockedBetween(a, b);
    }

    // ── Restriction: notification-path guard ──────────────────────────────

    /**
     * True when {@code recipientId} has restricted {@code actorId} —
     * notification systems use this to drop the alert silently while still
     * persisting the underlying activity (IG-style "restrict").
     */
    @Transactional(readOnly = true)
    public boolean isRestricting(UUID recipientId, UUID actorId) {
        if (recipientId == null || actorId == null || recipientId.equals(actorId)) return false;
        return restrictionRepo.isRestricting(recipientId, actorId);
    }

    // ── Block: feed-filter helpers ────────────────────────────────────────

    /**
     * Every user-id that is in any block relationship with {@code userId}
     * (either direction). One DB round-trip — feeds can pass the result
     * directly into a {@code NOT IN} clause.
     *
     * <p>Cached for 1 minute under {@code user-blocked-ids} so a hot feed
     * scroll does not re-issue the same query on every page. The cache is
     * evicted from {@code UserSocialServiceImpl} whenever the user's block
     * graph changes.</p>
     *
     * <p>Returns an empty list for null callers (anonymous viewers).</p>
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "user-blocked-ids", key = "#userId", unless = "#userId == null")
    public List<UUID> findRelatedBlockedIds(UUID userId) {
        if (userId == null) return List.of();
        return blockRepo.findAllRelatedBlockedIds(userId);
    }
}
