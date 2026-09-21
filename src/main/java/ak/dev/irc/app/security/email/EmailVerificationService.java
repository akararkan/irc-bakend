package ak.dev.irc.app.security.email;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.email.EmailService;
import ak.dev.irc.app.security.otp.OtpProperties;
import ak.dev.irc.app.security.otp.delivery.CodeEmail;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import ak.dev.irc.app.security.otp.service.OtpService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Confirms that the address already on an account is really the user's, by
 * mailing a one-time code to it. Sets {@code User.emailVerifiedAt} — the flag
 * behind the "Account recovery configured" and "Email address verified" items
 * of the security checkup ({@code SecurityScoreService}), worth 50 of its 100
 * points between them.
 *
 * <p>The address is <b>always read off the account</b>, never taken from the
 * request. Letting the caller name the destination would turn this into a way
 * to mark an address verified that the user does not own — and the flag is what
 * account recovery trusts.</p>
 *
 * <p>The challenge machinery (hashed code, Redis TTL, attempt ceiling, single
 * use, Postgres audit row) is {@link OtpService}'s, reached through its
 * destination-generic {@code issueFor}/{@code verifyFor} pair. Codes here are
 * scoped to {@link OtpPurpose#EMAIL_VERIFY}, so nothing minted for another flow
 * can be redeemed as proof of address ownership.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** Email arrives slower than SMS, and is read later — longer than the 5-minute phone code. */
    private static final long TTL_SECONDS = 900;
    private static final long TTL_MINUTES = TTL_SECONDS / 60;

    private final OtpService otpService;
    private final OtpProperties otpProps;
    private final EmailService emailService;
    private final UserRepository userRepo;
    private final RateLimiter rateLimiter;

    /** Mail a fresh code to the account's own address. */
    @Transactional
    public void requestVerification(UUID userId, String ip) {
        User user = requireUnverified(userId);
        String email = destination(user);

        // Same budget as the phone flow. Keyed on the account rather than the
        // address so a resend storm can't be spread across sessions.
        rateLimiter.check("otp:email", actorKey("acct:" + userId),
                otpProps.getResendPerNumberPerHour(), Duration.ofHours(1));
        if (ip != null && !ip.isBlank()) {
            rateLimiter.check("otp:email:ip", actorKey("ip:" + ip),
                    otpProps.getResendPerIpPerHour(), Duration.ofHours(1));
        }

        String code = otpService.issueFor(email, OtpPurpose.EMAIL_VERIFY, TTL_SECONDS, ip, null);
        String body = SecurityMessages.EMAIL_VERIFY_BODY.formatted(code, TTL_MINUTES);

        if (!emailService.isEnabled()) {
            // Mirrors LoggingSmsSender: with no transport configured the flow
            // still has to be exercisable end-to-end in local dev and CI.
            log.info("[EMAIL-DEV] to={} code={} purpose=EMAIL_VERIFY", email, code);
            return;
        }
        emailService.sendAsync(email, SecurityMessages.EMAIL_VERIFY_SUBJECT, body,
                CodeEmail.html("Verify your email address",
                        "Enter this code in the app to confirm this address.", code, TTL_MINUTES));
    }

    /**
     * Redeem a code and mark the address verified.
     *
     * @return the verified address
     * @throws BadRequestException on a wrong, expired or already-spent code
     */
    @Transactional
    public String confirm(UUID userId, String code) {
        User user = requireUnverified(userId);
        String email = destination(user);

        otpService.verifyFor(email, code, OtpPurpose.EMAIL_VERIFY);

        user.setEmailVerifiedAt(LocalDateTime.now());
        user.audit(AuditAction.EMAIL_VERIFY, "Email verified by user");
        userRepo.save(user);
        return user.getEmail();
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Both endpoints reject an already-verified account rather than quietly
     * re-issuing: the flag is one-way, so a second round trip has nothing to do
     * but let a stale UI burn the user's rate-limit budget.
     */
    private User requireUnverified(UUID userId) {
        User user = userRepo.findActiveById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isEmailVerified()) {
            throw new ConflictException(SecurityMessages.EMAIL_ALREADY_VERIFIED_MSG,
                    SecurityMessages.EMAIL_ALREADY_VERIFIED);
        }
        return user;
    }

    /**
     * Lower-cased so the challenge key is stable: mail addresses are matched
     * case-insensitively in practice, and issuing against {@code A@x.com} while
     * verifying against {@code a@x.com} would hash to two different challenges.
     */
    private String destination(User user) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new BadRequestException(SecurityMessages.EMAIL_MISSING_MSG,
                    SecurityMessages.EMAIL_MISSING);
        }
        return email.trim().toLowerCase();
    }

    /** Deterministic UUID key for the UUID-typed RateLimiter API. */
    private UUID actorKey(String seed) {
        return UUID.nameUUIDFromBytes(("otp:email:" + seed).getBytes());
    }
}
