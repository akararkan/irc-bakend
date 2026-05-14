package ak.dev.irc.app.post.realtime;

import ak.dev.irc.app.post.repository.PostViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Decides whether a post view should bump the displayed counter.
 *
 * <p>Authenticated users count <em>once per post, ever</em> — backed by the
 * {@code post_views} ledger table with an atomic
 * {@code INSERT ... ON CONFLICT DO NOTHING}. The second view from the same
 * user (a minute later, a year later, on a different device) is a no-op at
 * both the row and counter level.</p>
 *
 * <p>Anonymous viewers fall back to a 1-hour Redis dedupe on the request
 * fingerprint (typically {@code X-Forwarded-For} → IP) — the best we can do
 * without a stable identity. Refresh-spam from the same IP collapses into a
 * single counter bump per hour.</p>
 *
 * <p>Failure mode: if Redis is unreachable for the anonymous path, count
 * without dedupe — losing the dedupe is preferable to losing the view.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostViewTracker {

    private static final Duration ANON_DEDUPE_WINDOW = Duration.ofHours(1);
    private static final String   ANON_KEY_PREFIX    = "irc:view:anon:";

    private final StringRedisTemplate redisTemplate;
    private final PostViewRepository  postViewRepository;

    /**
     * Returns true if this view should bump the post's counter.
     *
     * @param postId    the post being viewed
     * @param userId    the authenticated user, or {@code null} for anonymous
     * @param viewerKey opaque request fingerprint for anonymous dedupe
     *                  (ignored when {@code userId} is non-null)
     */
    public boolean shouldCount(UUID postId, UUID userId, String viewerKey) {
        if (userId != null) {
            try {
                int inserted = postViewRepository.tryRecord(postId, userId);
                return inserted == 1;
            } catch (DataIntegrityViolationException duplicate) {
                // Race with another concurrent view from the same user — already
                // counted by the other thread. Skip this bump.
                return false;
            } catch (Exception ex) {
                // Ledger table unavailable — degrade to the anon Redis dedupe so
                // we don't lose the view entirely under DB pressure.
                log.warn("[POST-VIEW-LEDGER] tryRecord failed for post={} user={}: {}",
                        postId, userId, ex.getMessage());
                return anonShouldCount(postId, userId.toString());
            }
        }
        return anonShouldCount(postId, viewerKey);
    }

    /** Backwards-compat overload — treats every caller as anonymous. */
    public boolean shouldCount(UUID postId, String viewerKey) {
        return shouldCount(postId, null, viewerKey);
    }

    private boolean anonShouldCount(UUID postId, String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) viewerKey = "anon";
        String key = ANON_KEY_PREFIX + postId + ":" + viewerKey;
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(key, "1", ANON_DEDUPE_WINDOW);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            log.debug("[POST-VIEW-TRACKER] Redis unavailable, counting without dedupe: {}", ex.getMessage());
            return true;
        }
    }
}
