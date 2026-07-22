package ak.dev.irc.app.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Exactly-once <i>effect</i> for message sends. The client generates a
 * {@code clientNonce} once per message and reuses it across retries; the first
 * attempt claims the nonce with {@code SET NX EX} and creates the row, later
 * attempts read the already-created message id back and return it — so a timeout,
 * reconnect, or double-tap never produces a second message.
 *
 * <p>Fails open: if Redis is unreachable, a send is allowed (application-level
 * uniqueness is a best-effort guard, not a correctness dependency here).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatIdempotencyService {

    private static final Duration NONCE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    private static String key(UUID userId, String nonce) {
        return "chat:nonce:" + userId + ":" + nonce;
    }

    /**
     * Attempts to claim the nonce for {@code messageId}. Returns {@code true} on
     * the first (winning) attempt; {@code false} if a prior attempt already
     * claimed it.
     */
    public boolean claim(UUID userId, String nonce, long messageId) {
        try {
            Boolean won = redis.opsForValue()
                    .setIfAbsent(key(userId, nonce), Long.toString(messageId), NONCE_TTL);
            return !Boolean.FALSE.equals(won); // null (redis down) → allow
        } catch (Exception e) {
            log.debug("[CHAT-IDEMPOTENCY] redis unavailable — allowing send: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Release a claimed nonce after a send fails <b>before</b> a message was
     * persisted (blocked / capped / not-a-member), so a legitimate retry can
     * re-attempt instead of resolving to a never-created message.
     */
    public void release(UUID userId, String nonce) {
        try {
            redis.delete(key(userId, nonce));
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** The message id a previous attempt already created for this nonce, or null. */
    public Long existingMessageId(UUID userId, String nonce) {
        try {
            String v = redis.opsForValue().get(key(userId, nonce));
            return v == null ? null : Long.parseLong(v);
        } catch (Exception e) {
            return null;
        }
    }
}
