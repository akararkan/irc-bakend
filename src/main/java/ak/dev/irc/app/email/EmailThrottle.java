package ak.dev.irc.app.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed dedupe gate for outbound notification email.
 *
 * <p>An aggregated in-app notification ({@code POST_REACTED:abc-123}) can fire
 * many times inside the aggregation window — but the user should only receive
 * one email about it per window. {@link #shouldSend} stamps a Redis key with
 * {@code SET NX EX}, so the first call wins and every subsequent call within
 * the TTL returns {@code false}.</p>
 *
 * <p>One-shot notifications without a {@code groupKey} (system messages,
 * follow alerts, accept events) use the notification id as the dedupe key
 * — they're never repeated for the same row anyway, so this is mostly belt
 * and braces.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailThrottle {

    private static final String KEY_PREFIX = "irc:email:";

    private final StringRedisTemplate redis;

    @Value("${irc.email.throttle-minutes:60}")
    private long throttleMinutes;

    /**
     * @param userId      recipient's user id
     * @param dedupeKey   either the notification's {@code groupKey} for
     *                    aggregated rows or its id for one-shots
     * @return {@code true} if the caller should send the email, {@code false}
     *         if a recent send already covered this group
     */
    public boolean shouldSend(UUID userId, String dedupeKey) {
        if (userId == null || dedupeKey == null || dedupeKey.isBlank()) return true;
        String key = KEY_PREFIX + userId + ":" + dedupeKey;
        Duration ttl = Duration.ofMinutes(Math.max(1, throttleMinutes));
        try {
            Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            // If Redis is down, fail open — losing a duplicate email is far
            // less harmful than dropping legitimate alerts entirely.
            log.debug("[EMAIL-THROTTLE] Redis unavailable, skipping dedupe: {}", ex.getMessage());
            return true;
        }
    }
}
