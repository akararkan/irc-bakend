package ak.dev.irc.app.security.otp.service;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.security.crypto.Hashing;
import ak.dev.irc.app.security.otp.OtpProperties;
import ak.dev.irc.app.security.otp.entity.OtpChallenge;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import ak.dev.irc.app.security.otp.repository.OtpChallengeRepository;
import ak.dev.irc.app.security.otp.sms.SmsSender;
import ak.dev.irc.app.security.phone.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One-Time Password engine (spec §2.2, §2.4). Implements the full spec machinery
 * with pure JDK crypto:
 *
 * <ul>
 *   <li>6-digit code from {@link SecureRandom}; the plaintext is never stored —
 *       only {@code HMAC-SHA256(code, pepper)}.</li>
 *   <li>TTL in Redis ({@code otp:{purpose}:{destHash}}) with the Postgres row as
 *       audit; Redis expiry does the cleanup for free.</li>
 *   <li>Max attempts per challenge, then the challenge is burned.</li>
 *   <li>Resend rate-limited per number and per IP (Redis sliding window).</li>
 *   <li><b>Enumeration protection:</b> {@link #requestOtp} returns nothing and
 *       never reveals whether the number is registered; the controller answers
 *       202 unconditionally.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpProperties props;
    private final PhoneNormalizer phoneNormalizer;
    private final OtpChallengeRepository challengeRepo;
    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final SmsSender smsSender;

    private final SecureRandom random = new SecureRandom();

    /**
     * Issue a code to {@code rawPhone}. Enumeration-safe: same behaviour whether
     * or not the number is registered.
     *
     * @return the normalised E.164 (for the caller's own bookkeeping — NOT to be
     *         surfaced to the client in a way that reveals registration).
     */
    @Transactional
    public String requestOtp(String rawPhone, OtpPurpose purpose, String ip, String deviceId) {
        String e164 = phoneNormalizer.toE164(rawPhone);
        OtpPurpose p = purpose == null ? OtpPurpose.LOGIN : purpose;

        // Resend rate limits — per number and per IP (Redis sliding window).
        rateLimiter.check("otp:num", actorKey("num:" + e164),
                props.getResendPerNumberPerHour(), Duration.ofHours(1));
        if (ip != null && !ip.isBlank()) {
            rateLimiter.check("otp:ip", actorKey("ip:" + ip),
                    props.getResendPerIpPerHour(), Duration.ofHours(1));
        }

        String code = generateCode();
        String destHash = Hashing.hmacSha256Hex(e164, props.getPepper());
        String codeHash = Hashing.hmacSha256Hex(code, props.getPepper());

        // Redis holds the live challenge: the hashed code plus its own attempts
        // counter, both under the same TTL so expiry cleans up for free. A new
        // request overwrites the previous pair, which is what retires an older
        // code the moment a fresh one is sent.
        Duration ttl = Duration.ofSeconds(props.getTtlSeconds());
        redis.opsForValue().set(redisKey(p, destHash), codeHash, ttl);
        redis.opsForValue().set(attemptsKey(p, destHash), "0", ttl);

        // Postgres: durable audit trail + authoritative attempts counter.
        challengeRepo.save(OtpChallenge.builder()
                .destinationHash(destHash)
                .codeHash(codeHash)
                .purpose(p)
                .expiresAt(LocalDateTime.now().plusSeconds(props.getTtlSeconds()))
                .ip(ip)
                .deviceId(deviceId)
                .build());

        smsSender.send(e164, SecurityMessages.NOTIF_OTP_SMS
                .formatted(code, props.getTtlSeconds() / 60));
        log.debug("[OTP] issued purpose={} destHash={}…", p, destHash.substring(0, 8));
        return e164;
    }

    /**
     * Verify a code. On success the challenge is consumed and the verified E.164
     * is returned. On failure attempts are incremented and the challenge is
     * burned once the max is reached.
     *
     * @throws BadRequestException on any invalid/expired/exhausted challenge.
     */
    @Transactional
    public String verifyOtp(String rawPhone, String code, OtpPurpose purpose) {
        String e164 = phoneNormalizer.toE164(rawPhone);
        OtpPurpose p = purpose == null ? OtpPurpose.LOGIN : purpose;
        String destHash = Hashing.hmacSha256Hex(e164, props.getPepper());

        // Redis is authoritative for the live challenge; the Postgres row is the
        // audit trail. Reading the hash here (rather than off the row) is also
        // what makes expiry exact — the key is simply gone.
        String storedHash;
        try {
            storedHash = redis.opsForValue().get(redisKey(p, destHash));
        } catch (Exception ex) {
            // Fail CLOSED: without Redis there is no attempt ceiling, so a
            // degraded store must not become an unmetered guessing oracle.
            log.warn("[OTP] challenge store unavailable on verify: {}", ex.getMessage());
            throw invalid();
        }
        if (storedHash == null) throw invalid();     // expired, never issued, or spent

        // Atomic increment — two concurrent guesses cannot both read the same
        // count and slip past the ceiling, which a read-modify-write on the
        // Postgres row allowed.
        Long attempts = redis.opsForValue().increment(attemptsKey(p, destHash));
        if (attempts != null && attempts == 1L) {
            // Counter recreated after its TTL lapsed — re-arm it so it cannot
            // outlive the code it guards.
            redis.expire(attemptsKey(p, destHash), Duration.ofSeconds(props.getTtlSeconds()));
        }
        if (attempts == null || attempts > props.getMaxAttempts()) {
            burn(p, destHash);
            recordAttempt(destHash, p, true);
            throw invalid();
        }

        String candidate = Hashing.hmacSha256Hex(code == null ? "" : code.trim(), props.getPepper());
        if (!Hashing.constantTimeEquals(candidate, storedHash)) {
            recordAttempt(destHash, p, false);
            throw invalid();
        }

        // Single use: delete first, and only the caller whose delete actually
        // removed the key may proceed.
        Boolean won = redis.delete(redisKey(p, destHash));
        if (!Boolean.TRUE.equals(won)) throw invalid();
        redis.delete(attemptsKey(p, destHash));

        challengeRepo.findFirstByDestinationHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(destHash, p)
                .ifPresent(row -> {
                    row.setConsumedAt(LocalDateTime.now());
                    challengeRepo.save(row);
                });
        return e164;
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private String generateCode() {
        int bound = (int) Math.pow(10, props.getCodeLength());
        int n = random.nextInt(bound);
        return String.format("%0" + props.getCodeLength() + "d", n);
    }

    private String redisKey(OtpPurpose p, String destHash) {
        return "otp:" + p.name() + ":" + destHash;
    }

    private String attemptsKey(OtpPurpose p, String destHash) {
        return "otp:att:" + p.name() + ":" + destHash;
    }

    /** Retire a challenge outright (attempt ceiling blown). */
    private void burn(OtpPurpose p, String destHash) {
        try {
            redis.delete(redisKey(p, destHash));
            redis.delete(attemptsKey(p, destHash));
        } catch (Exception ex) {
            log.debug("[OTP] burn failed: {}", ex.getMessage());
        }
    }

    /**
     * Mirror a failed attempt onto the audit row. Best-effort: the ceiling that
     * actually protects the code lives in Redis, so a bookkeeping failure here
     * must never turn into a login failure.
     */
    private void recordAttempt(String destHash, OtpPurpose p, boolean exhausted) {
        try {
            challengeRepo.findFirstByDestinationHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(destHash, p)
                    .ifPresent(row -> {
                        row.setAttempts(row.getAttempts() + 1);
                        if (exhausted) row.setConsumedAt(LocalDateTime.now());
                        challengeRepo.save(row);
                    });
        } catch (Exception ex) {
            log.debug("[OTP] attempt audit failed: {}", ex.getMessage());
        }
    }

    /** Deterministic UUID key for the UUID-typed RateLimiter API. */
    private UUID actorKey(String seed) {
        return UUID.nameUUIDFromBytes(("otp:" + seed).getBytes());
    }

    private BadRequestException invalid() {
        return new BadRequestException(SecurityMessages.OTP_INVALID_MSG, SecurityMessages.OTP_INVALID);
    }
}
