package ak.dev.irc.app.security.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis denylist of revoked session ids (spec §2.4/§12). When a session is
 * revoked its {@code sid} is written here with the remaining access-token TTL,
 * so the 15-minute stateless-JWT window is closed immediately rather than waited
 * out.
 *
 * <p><b>Seam:</b> to make this effective on the request path, add a
 * {@code sid}-claim check to {@code JwtAuthenticationFilter} that rejects a token
 * whose {@code sid} is {@link #isDenied}. Tokens without a {@code sid} claim are
 * unaffected, so this is safe to wire incrementally.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionDenylist {

    private final StringRedisTemplate redis;

    public void deny(UUID sid, Duration ttl) {
        if (sid == null) return;
        try {
            redis.opsForValue().set(key(sid), "1", ttl == null ? Duration.ofMinutes(15) : ttl);
        } catch (Exception ex) {
            log.debug("[SESSION-DENYLIST] deny failed for {}: {}", sid, ex.getMessage());
        }
    }

    public boolean isDenied(UUID sid) {
        if (sid == null) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(sid)));
        } catch (Exception ex) {
            return false;
        }
    }

    private String key(UUID sid) {
        return "sid:denied:" + sid;
    }
}
