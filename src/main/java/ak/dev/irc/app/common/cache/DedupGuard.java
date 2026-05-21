package ak.dev.irc.app.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Short-window deduplication for "submit something" actions.
 *
 * Catches the common double-fire patterns:
 *   • Double-click before the UI disables the submit button.
 *   • Network retry on a timeout that actually succeeded.
 *   • Optimistic-insert + realtime echo arriving twice.
 *
 * Uses Redis SET-NX with a short TTL so the same (namespace, scope, actor,
 * text-hash) tuple only succeeds once per window. If Redis is unreachable,
 * fails open (allows the write) — correctness still relies on application
 * uniqueness, this is just a fast guard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DedupGuard {

    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(3);

    private final StringRedisTemplate redis;

    /**
     * @param namespace short tag identifying the action (e.g. {@code "post-comment"})
     * @param scope     the entity being commented on / answered (postId, questionId, researchId)
     * @param actorId   the authenticated user
     * @param text      the content body — used to differentiate semantically distinct submissions
     * @return {@code true} if this tuple was already seen inside the dedup window
     */
    public boolean isDuplicate(String namespace, UUID scope, UUID actorId, String text) {
        return isDuplicate(namespace, scope, actorId, text, DEFAULT_WINDOW);
    }

    public boolean isDuplicate(String namespace, UUID scope, UUID actorId, String text, Duration window) {
        if (scope == null || actorId == null) return false;
        try {
            String key = "dedup:" + namespace + ":" + scope + ":" + actorId + ":" + hash(text);
            Boolean firstWrite = redis.opsForValue().setIfAbsent(key, "1", window);
            return Boolean.FALSE.equals(firstWrite);
        } catch (Exception e) {
            log.debug("[DEDUP] redis unavailable for {} — allowing write: {}", namespace, e.getMessage());
            return false;
        }
    }

    private static String hash(String s) {
        if (s == null) return "_";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
