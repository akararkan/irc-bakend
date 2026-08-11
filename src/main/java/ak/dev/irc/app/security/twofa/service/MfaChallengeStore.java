package ak.dev.irc.app.security.twofa.service;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.SecurityMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Server-side state for in-flight <b>MFA login challenges</b>.
 *
 * <p>The challenge token itself is a signed, short-TTL JWT — but a signature
 * alone cannot express "this may be tried at most N times and redeemed at most
 * once". That state lives here, keyed by the token's {@code jti}:</p>
 *
 * <ul>
 *   <li><b>Brute-force cap.</b> A 6-digit TOTP is a 10<sup>6</sup> space and the
 *       ±1 drift window makes 3 codes valid at any instant. Without a cap an
 *       attacker holding a challenge token (i.e. one who already has the
 *       password) could simply guess. Attempts are counted with an atomic Redis
 *       {@code INCR}, so parallel guesses cannot race past the ceiling.</li>
 *   <li><b>Single use.</b> Redemption deletes the key and only the caller whose
 *       {@code DEL} actually removed it wins — two concurrent redemptions of the
 *       same challenge cannot both mint a session.</li>
 *   <li><b>Revocability.</b> Blowing the attempt ceiling burns the challenge
 *       immediately; the user must re-enter their password to get a new one.</li>
 * </ul>
 *
 * <p><b>This gate fails CLOSED.</b> Every other Redis-backed limiter on the
 * platform fails open (availability over strictness), but here an unreachable
 * Redis would mean unlimited, unmetered guessing against the second factor. If
 * the store is unavailable the challenge is treated as invalid and the user
 * re-authenticates once Redis is back — a brief login outage for 2FA accounts
 * is the correct trade against silently disabling the brute-force ceiling.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaChallengeStore {

    private static final String CHALLENGE_PREFIX = "mfa:chal:";
    private static final String ATTEMPTS_PREFIX  = "mfa:att:";

    private final StringRedisTemplate redis;

    /** Register a freshly minted challenge. Both keys carry the token's own TTL. */
    public void issue(String jti, Duration ttl) {
        try {
            redis.opsForValue().set(challengeKey(jti), "1", ttl);
            redis.opsForValue().set(attemptsKey(jti), "0", ttl);
        } catch (Exception ex) {
            log.warn("[MFA] challenge store unavailable on issue: {}", ex.getMessage());
            throw invalid();
        }
    }

    /**
     * Count one verification attempt against a live challenge. Burns the
     * challenge — and throws — once {@code maxAttempts} is exceeded.
     *
     * @throws UnauthorizedException if the challenge is unknown, already
     *         redeemed, expired, or out of attempts.
     */
    public void registerAttempt(String jti, int maxAttempts) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(challengeKey(jti)))) {
                throw invalid();       // never issued, already redeemed, or expired
            }
            Long attempts = redis.opsForValue().increment(attemptsKey(jti));
            if (attempts != null && attempts == 1L) {
                // The counter was recreated by this INCR (its TTL had lapsed just
                // ahead of the challenge key's). Re-arm the expiry so a stray
                // counter can never outlive its challenge.
                redis.expire(attemptsKey(jti), Duration.ofMinutes(15));
            }
            if (attempts == null || attempts > maxAttempts) {
                burn(jti);
                throw new UnauthorizedException(
                        SecurityMessages.MFA_TOO_MANY_ATTEMPTS_MSG,
                        SecurityMessages.MFA_TOO_MANY_ATTEMPTS);
            }
        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[MFA] challenge store unavailable on attempt: {}", ex.getMessage());
            throw invalid();
        }
    }

    /**
     * Redeem the challenge exactly once. Returns normally only for the caller
     * whose delete actually removed the key.
     */
    public void consume(String jti) {
        boolean won;
        try {
            won = Boolean.TRUE.equals(redis.delete(challengeKey(jti)));
            redis.delete(attemptsKey(jti));
        } catch (Exception ex) {
            log.warn("[MFA] challenge store unavailable on consume: {}", ex.getMessage());
            throw invalid();
        }
        if (!won) throw invalid();     // lost the race, or it was already spent
    }

    /** Drop a challenge without redeeming it (attempt ceiling blown). */
    public void burn(String jti) {
        try {
            redis.delete(challengeKey(jti));
            redis.delete(attemptsKey(jti));
        } catch (Exception ex) {
            log.debug("[MFA] burn failed for {}: {}", jti, ex.getMessage());
        }
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException(
                SecurityMessages.MFA_CHALLENGE_INVALID_MSG, SecurityMessages.MFA_CHALLENGE_INVALID);
    }

    private static String challengeKey(String jti) { return CHALLENGE_PREFIX + jti; }
    private static String attemptsKey(String jti)  { return ATTEMPTS_PREFIX + jti; }
}
