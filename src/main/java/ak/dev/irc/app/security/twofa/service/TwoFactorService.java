package ak.dev.irc.app.security.twofa.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.security.crypto.SecretCipher;
import ak.dev.irc.app.security.twofa.TwoFaProperties;
import ak.dev.irc.app.security.twofa.TotpProvider;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * TOTP two-factor authentication (spec §12).
 *
 * <ul>
 *   <li>Secret generated server-side, returned once as a provisioning URI, then
 *       stored <b>encrypted at rest</b> (AES-GCM via {@link SecretCipher}).</li>
 *   <li>Enrolment is committed only after the user verifies one code — otherwise
 *       a mis-scanned QR locks the user out.</li>
 *   <li><b>Replay protection:</b> the last accepted step index is stored per
 *       user; the same code cannot be used twice.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private final UserRepository userRepo;
    private final TotpProvider totp;
    private final SecretCipher cipher;
    private final TwoFaProperties props;

    public record SetupResult(String provisioningUri, String secret) {}

    /** Begin enrolment: mint + store an (encrypted) secret, return the QR URI once. */
    @Transactional
    public SetupResult setup(UUID userId) {
        User user = user(userId);
        if (user.isTwoFactorEnabled()) {
            throw new ConflictException(SecurityMessages.TWO_FA_ALREADY_ON_MSG, SecurityMessages.TWO_FA_ALREADY_ON);
        }
        String secret = totp.generateSecret();
        user.setTwoFactorSecret(cipher.encrypt(secret));
        user.setTwoFactorLastStep(null);
        userRepo.save(user);
        String uri = totp.provisioningUri(props.getIssuer(), user.getUsername(), secret);
        return new SetupResult(uri, secret);
    }

    /**
     * Confirm enrolment with the first code. On success, 2FA is switched on.
     *
     * @return true if this call newly enabled 2FA.
     */
    @Transactional
    public boolean confirmEnable(UUID userId, String code) {
        User user = user(userId);
        if (user.getTwoFactorSecret() == null) {
            throw new BadRequestException(SecurityMessages.TWO_FA_NOT_STARTED_MSG, SecurityMessages.TWO_FA_NOT_STARTED);
        }
        if (!verifyInternal(user, code)) {
            throw new BadRequestException(SecurityMessages.TWO_FA_INVALID_MSG, SecurityMessages.TWO_FA_INVALID);
        }
        boolean newlyEnabled = !user.isTwoFactorEnabled();
        user.setTwoFactorEnabled(true);
        userRepo.save(user);
        return newlyEnabled;
    }

    /** Verify a TOTP code for an already-enrolled user (step-up / login). */
    @Transactional
    public boolean verifyCode(UUID userId, String code) {
        User user = user(userId);
        if (!user.isTwoFactorEnabled() || user.getTwoFactorSecret() == null) return false;
        return verifyInternal(user, code);
    }

    @Transactional
    public void disable(UUID userId) {
        User user = user(userId);
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.setTwoFactorLastStep(null);
        userRepo.save(user);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private boolean verifyInternal(User user, String code) {
        String secret = cipher.decrypt(user.getTwoFactorSecret());
        long now = System.currentTimeMillis() / 1000L;
        long last = user.getTwoFactorLastStep() == null ? -1L : user.getTwoFactorLastStep();
        long matched = totp.verify(secret, code, now, props.getAllowedDriftSteps(), last);
        if (matched < 0) return false;
        user.setTwoFactorLastStep(matched);   // replay guard advance
        userRepo.save(user);
        return true;
    }

    private User user(UUID userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}
