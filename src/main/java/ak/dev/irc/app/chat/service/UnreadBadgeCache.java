package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

/**
 * Total unread-badge cache. The per-conversation counts are authoritative in
 * Postgres ({@code conversation_members.unread_count}); this caches only their
 * <i>sum</i> for the global badge so the app-icon count doesn't re-sum on every
 * poll. Rather than track deltas (which drift), any unread change simply
 * {@link #invalidate(UUID)}s the key and the next read recomputes — correct and
 * cheap. Fails open to a live DB sum if Redis is down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadBadgeCache {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String PREFIX = "chat:unread:";

    private final StringRedisTemplate redis;
    private final ConversationMemberRepository memberRepo;

    @Transactional(readOnly = true)
    public long total(UUID userId) {
        String key = PREFIX + userId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return Long.parseLong(cached);
        } catch (Exception ignored) { /* fall through to DB */ }

        long sum = memberRepo.sumUnread(userId);
        try {
            redis.opsForValue().set(key, Long.toString(sum), TTL);
        } catch (Exception ignored) { /* best-effort */ }
        return sum;
    }

    /**
     * Invalidate the badge cache. Deferred until <b>after commit</b> when a
     * transaction is active: clearing before commit would let a concurrent read
     * re-cache the pre-change (still-committed) sum and leave the badge stale.
     */
    public void invalidate(UUID userId) {
        if (userId == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(userId); }
            });
        } else {
            evict(userId);
        }
    }

    private void evict(UUID userId) {
        try {
            redis.delete(PREFIX + userId);
        } catch (Exception e) {
            log.debug("[UNREAD-BADGE] invalidate failed for {}: {}", userId, e.getMessage());
        }
    }
}
