package ak.dev.irc.app.post.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Dedupes post views so refreshing the same tab doesn't inflate counters.
 *
 * <p>Authenticated viewers are keyed by user id, anonymous viewers by request
 * fingerprint (typically client IP). A {@code SET NX EX} on Redis decides
 * whether this is a fresh view within the window.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostViewTracker {

    private static final Duration DEDUPE_WINDOW = Duration.ofHours(1);
    private static final String   KEY_PREFIX    = "irc:view:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Returns true if this is the first view from {@code viewerKey} for
     * {@code postId} within the dedupe window. Caller increments the counter
     * only when this returns true.
     */
    public boolean shouldCount(UUID postId, String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) viewerKey = "anon";
        String key = KEY_PREFIX + postId + ":" + viewerKey;
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(key, "1", DEDUPE_WINDOW);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            // If Redis is down, fall back to counting — losing dedupe is preferable
            // to losing the view entirely.
            log.debug("[VIEW-TRACKER] Redis unavailable, counting without dedupe: {}", ex.getMessage());
            return true;
        }
    }
}
