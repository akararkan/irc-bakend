package ak.dev.irc.app.security.otp.delivery;

import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.email.EmailService;
import ak.dev.irc.app.security.otp.sms.SmsSender;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides <em>how</em> an OTP code reaches its owner (spec §2.4).
 *
 * <p>The code itself is always generated, hashed and stored the same way — this
 * only picks the transport, so switching channels never touches the auth logic:
 * <ul>
 *   <li>{@code email} (default) — mails the code to the account's address. No
 *       SMS gateway contract, no per-message cost, works today.</li>
 *   <li>{@code sms} — hands off to {@link SmsSender}. Until a real gateway bean
 *       replaces {@code LoggingSmsSender} this only writes to the log.</li>
 *   <li>{@code log} — never sends anything; the code appears in the application
 *       log only. For local development and CI.</li>
 * </ul>
 *
 * <p><b>Why the email address is passed in rather than looked up.</b> Phone
 * <em>verification</em> is the case where the number is not yet bound to any
 * account, so resolving the recipient from the phone number cannot work — the
 * caller, which knows the logged-in user, supplies the address. The lookup by
 * phone is only the fallback for pre-auth flows (phone login), where the number
 * is by definition already bound.</p>
 *
 * <p>Delivery is best-effort and never fails the request: an OTP that cannot be
 * delivered must still leave the challenge valid, and surfacing a transport
 * error to the caller would leak whether the number/address exists.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpDeliveryService {

    private final SmsSender smsSender;
    private final EmailService emailService;
    private final UserRepository userRepo;

    /** {@code email} | {@code sms} | {@code log}. */
    @Value("${app.otp.delivery:email}")
    private String channel;

    /**
     * Deliver a freshly issued code.
     *
     * @param e164        destination phone in E.164 — the number being verified,
     *                    or the number being logged in with
     * @param emailHint   the account's email when the caller knows it; null lets
     *                    this resolve the owner from {@code e164}
     * @param code        the plaintext code (never persisted anywhere)
     * @param ttlMinutes  how long it stays valid, for the copy
     */
    public void deliver(String e164, String emailHint, String code, long ttlMinutes) {
        String body = SecurityMessages.NOTIF_OTP_SMS.formatted(code, ttlMinutes);

        if ("sms".equalsIgnoreCase(channel)) {
            smsSender.send(e164, body);
            return;
        }
        if ("log".equalsIgnoreCase(channel)) {
            log.info("[OTP-DEV] to={} body=\"{}\" (app.otp.delivery=log — nothing sent)", e164, body);
            return;
        }

        String to = emailHint != null && !emailHint.isBlank() ? emailHint : resolveEmailByPhone(e164);
        if (to == null) {
            // No address to send to. Falling back to the log keeps local dev and
            // not-yet-registered numbers working instead of dead-ending.
            log.warn("[OTP] no email for {} — falling back to log. body=\"{}\"", e164, body);
            return;
        }
        if (!emailService.isEnabled()) {
            log.warn("[OTP] email transport disabled — code for {} not delivered. "
                    + "Set MAIL_* / irc.email.enabled.", to);
            return;
        }

        emailService.sendAsync(to, SecurityMessages.EMAIL_VERIFY_SUBJECT, body,
                CodeEmail.html("Your verification code",
                        "Enter this code to confirm your phone number.", code, ttlMinutes));
        log.debug("[OTP] code mailed to {}", to);
    }

    private String resolveEmailByPhone(String e164) {
        return userRepo.findActiveByPhoneE164(e164)
                .map(u -> u.getEmail())
                .filter(e -> e != null && !e.isBlank())
                .orElse(null);
    }

}
