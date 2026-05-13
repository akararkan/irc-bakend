package ak.dev.irc.app.common.cache;

import ak.dev.irc.app.common.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                    Redis-Backed Rate Limiter                             │
 * │                                                                          │
 * │  Sliding-window counter using {@code INCR + EXPIRE} on a per-action      │
 * │  Redis key. Each call to {@link #check} increments the bucket and        │
 * │  throws {@link RateLimitExceededException} if the bucket is over the     │
 * │  burst limit for the window.                                             │
 * │                                                                          │
 * │  Keys: {@code rl:{action}:{actorId}:{windowEpochSeconds}}                │
 * │  TTL:  one window length (Redis auto-cleans).                            │
 * │                                                                          │
 * │  Failure mode: if Redis is unreachable, the limiter logs and lets the    │
 * │  request through. Availability beats throughput protection.              │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redis;

    /**
     * Throws when the actor exceeded {@code burst} calls within {@code window}
     * for the given action. Call this once per protected handler entry.
     */
    public void check(String action, UUID actorId, int burst, Duration window) {
        if (actorId == null) return;        // unauthenticated paths aren't limited here
        long now = System.currentTimeMillis() / 1000L;
        long bucket = now - (now % window.getSeconds());
        String key = "rl:" + action + ":" + actorId + ":" + bucket;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First request in this window — set the expiry so the key cleans up.
                redis.expire(key, window);
            }
            if (count != null && count > burst) {
                long retryAfter = window.getSeconds() - (now - bucket);
                log.debug("[RATE-LIMIT] {} blocked actor={} count={} burst={}",
                        action, actorId, count, burst);
                throw new RateLimitExceededException(action, Math.max(1L, retryAfter));
            }
        } catch (RateLimitExceededException up) {
            throw up;
        } catch (Exception ex) {
            // Redis unreachable — degrade gracefully, log and allow the call.
            log.debug("[RATE-LIMIT] {} fail-open for actor={}: {}", action, actorId, ex.getMessage());
        }
    }

    // ── Convenience pre-tuned bursts (per-action defaults) ─────────────────

    /** Reactions: 30 per 10 seconds per actor — generous but stops the spammer. */
    public void checkReaction(UUID actorId) { check("reaction", actorId, 30, Duration.ofSeconds(10)); }

    /** Comments / answers: 10 writes per 30 s. */
    public void checkComment(UUID actorId)  { check("comment",  actorId, 10, Duration.ofSeconds(30)); }

    /** Saves / shares: 30 per minute. */
    public void checkSocial(UUID actorId)   { check("social",   actorId, 30, Duration.ofMinutes(1)); }
}
