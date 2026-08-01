package ak.dev.irc.app.security.stepup;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Step-up authentication (spec §22.3). Disabling 2FA, changing the recovery
 * phone, or requesting account deletion prompts for the password (or a fresh
 * OTP) even inside a valid session — a stolen unlocked device must not be able
 * to dismantle the account's security.
 *
 * <p>A successful re-auth writes a short-lived Redis marker; sensitive handlers
 * call {@link #requireRecentStepUp} which throws {@link ForbiddenException}
 * ({@code STEP_UP_REQUIRED}) if the marker is missing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepUpService {

    private final StringRedisTemplate redis;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final StepUpProperties props;

    /** Re-authenticate with the account password; on success, arm the step-up window. */
    @Transactional(readOnly = true)
    public void stepUpWithPassword(UUID userId, String rawPassword) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.getPassword() == null || rawPassword == null
                || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BadRequestException("Password is incorrect.", "STEP_UP_BAD_PASSWORD");
        }
        arm(userId);
    }

    /** Arm the step-up window directly — used after a successful fresh OTP/2FA verify. */
    public void arm(UUID userId) {
        try {
            redis.opsForValue().set(key(userId), String.valueOf(Instant.now().getEpochSecond()),
                    Duration.ofSeconds(props.getTtlSeconds()));
        } catch (Exception ex) {
            log.debug("[STEP-UP] arm failed for {}: {}", userId, ex.getMessage());
        }
    }

    public boolean hasRecentStepUp(UUID userId) {
        try {
            return redis.hasKey(key(userId));
        } catch (Exception ex) {
            // Fail closed on Redis error for a security gate.
            log.debug("[STEP-UP] check failed for {}: {}", userId, ex.getMessage());
            return false;
        }
    }

    /** Guard a sensitive handler. Throws 403 {@code STEP_UP_REQUIRED} if not armed. */
    public void requireRecentStepUp(UUID userId) {
        if (!hasRecentStepUp(userId)) {
            throw new ForbiddenException(
                    "This action requires you to confirm your identity.", "STEP_UP_REQUIRED");
        }
    }

    private String key(UUID userId) {
        return "stepup:" + userId;
    }
}
