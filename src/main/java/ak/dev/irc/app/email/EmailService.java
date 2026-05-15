package ak.dev.irc.app.email;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Async, retrying SMTP send.
 *
 * <p>The send is fire-and-forget from the caller's perspective — failures
 * are logged with full context but never surface as exceptions, so a flaky
 * SMTP host can never break a notification write or a user request. Three
 * retry attempts with exponential backoff cover transient errors (rate
 * limits, brief network blips). After that the email is dropped — falling
 * back to the in-app notification, which is already persisted.</p>
 *
 * <p>Deliverability hardening — every outbound message carries:
 * <ul>
 *   <li>{@code Reply-To}: the configured address so replies don't bounce.</li>
 *   <li>{@code List-Unsubscribe} + {@code List-Unsubscribe-Post} (RFC 8058
 *       one-click) so Gmail / Yahoo / Apple Mail show a native unsubscribe
 *       button — a strong positive deliverability signal.</li>
 *   <li>Distinct {@code Message-Id} per send for thread integrity.</li>
 *   <li>{@code X-Entity-Ref-Id} to help recipients dedupe forwarded mail.</li>
 *   <li>{@code Auto-Submitted: auto-generated} so vacation responders skip it.</li>
 * </ul>
 * If your account-level SPF / DKIM is not aligned, mail will still go through
 * but will likely land in spam — these headers minimise that risk.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final int RETRY_ATTEMPTS = 3;
    private static final String MAILER_TAG  = "IRC-Platform/1.0";

    private final JavaMailSender mailSender;

    /**
     * Optional Resend HTTPS transport — present only when
     * {@code irc.email.provider=resend} (the recommended setting on any
     * cloud host that blocks outbound SMTP, including Railway). When null,
     * the service falls back to SMTP via {@link JavaMailSender}.
     */
    @Autowired(required = false)
    private ResendEmailSender resendSender;

    @Value("${irc.email.from-address}")
    private String fromAddress;

    @Value("${irc.email.from-name:IRC Platform}")
    private String fromName;

    @Value("${irc.email.enabled:true}")
    private boolean enabled;

    @Value("${irc.email.provider:smtp}")
    private String provider;

    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    public boolean isEnabled() {
        return enabled
                && fromAddress != null
                && !fromAddress.isBlank();
    }

    /** True iff the active provider is Resend AND its client is initialised. */
    private boolean resendActive() {
        return "resend".equalsIgnoreCase(provider) && resendSender != null && resendSender.isReady();
    }

    @PostConstruct
    public void announceConfig() {
        log.info("[EMAIL] startup — enabled={} provider={} from={} fromName={}",
                isEnabled(), provider, fromAddress, fromName);

        if (fromAddress == null || fromAddress.isBlank()) {
            log.error("[EMAIL] ❌ irc.email.from-address is empty — set MAIL_FROM "
                    + "(or, with Resend, the verified sender) on Railway.");
        }
        if (!isEnabled()) {
            log.warn("[EMAIL] ⚠ Email service disabled — no notification emails will be sent.");
            return;
        }

        if ("resend".equalsIgnoreCase(provider)) {
            if (resendSender == null || !resendSender.isReady()) {
                log.error("[EMAIL] ❌ provider=resend but ResendEmailSender is not initialised. "
                        + "Set RESEND_API_KEY on Railway → Variables and redeploy.");
            } else {
                log.info("[EMAIL] ✅ Active transport: Resend (HTTPS) — Railway-friendly, no SMTP egress required.");
            }
            return;
        }

        // SMTP path — only relevant in environments that allow outbound SMTP.
        String host = "<unknown>";
        int port = -1;
        String username = "<unknown>";
        String password = null;
        if (mailSender instanceof JavaMailSenderImpl impl) {
            host = impl.getHost();
            port = impl.getPort();
            username = impl.getUsername();
            password = impl.getPassword();
        }
        log.info("[EMAIL] SMTP transport selected — host={} port={} username={} passwordSet={}",
                host, port, username, password != null && !password.isBlank());
        if (host != null && host.contains("@")) {
            log.error("[EMAIL] ❌ SMTP host '{}' looks like an email address — set spring.mail.host "
                    + "(MAIL_HOST env var) to your SMTP server.", host);
        }
        if (username == null || username.isBlank()) {
            log.error("[EMAIL] ❌ MAIL_USERNAME is empty.");
        }
        if (password == null || password.isBlank()) {
            log.error("[EMAIL] ❌ MAIL_PASSWORD is empty.");
        }
        if (mailSender instanceof JavaMailSenderImpl impl) {
            try {
                impl.testConnection();
                log.info("[EMAIL] ✅ SMTP connection test succeeded — host={} username={}", host, username);
            } catch (MessagingException ex) {
                log.error("[EMAIL] ❌ SMTP connection test FAILED — host={} port={} username={}: {}",
                        host, port, username, ex.getMessage());
                log.error("[EMAIL]    Most cloud hosts (Railway, Heroku, Render, Fly, GCP) block outbound "
                        + "SMTP. Switch to provider=resend and set RESEND_API_KEY.");
            }
        }
    }

    /**
     * Send a transactional email with hardened headers for deliverability.
     */
    @Async("emailExecutor")
    public void sendAsync(String toAddress,
                          String subject,
                          String plainBody,
                          String htmlBody) {
        if (!isEnabled()) {
            log.debug("[EMAIL] disabled — would have sent '{}' to {}", subject, toAddress);
            return;
        }
        if (toAddress == null || toAddress.isBlank()) return;

        // Resend (HTTPS) — no SMTP egress, works on Railway / every PaaS.
        if (resendActive()) {
            sendViaResend(toAddress, subject, plainBody, htmlBody);
            return;
        }

        long backoffMs = 500;
        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(
                        message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
                helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
                helper.setReplyTo(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
                helper.setTo(toAddress);
                helper.setSubject(subject);
                helper.setText(plainBody == null ? "" : plainBody,
                               htmlBody  == null ? "" : htmlBody);

                // ── Headers that move us out of the spam folder ───────────
                String entityRef = UUID.randomUUID().toString();
                String hostPart = fromAddress.contains("@")
                        ? fromAddress.substring(fromAddress.indexOf('@') + 1)
                        : "irc";
                String messageId = "<" + entityRef + "@" + hostPart + ">";
                message.setHeader("Message-ID", messageId);
                message.setHeader("X-Entity-Ref-Id", entityRef);
                message.setHeader("X-Mailer", MAILER_TAG);
                message.setHeader("Auto-Submitted", "auto-generated");
                message.setHeader("Precedence", "bulk");

                // RFC 2369 + RFC 8058 — Gmail / Apple / Outlook surface a
                // native unsubscribe button when these are present, which
                // also acts as a positive reputation signal.
                String unsubUrl = baseUrl + "/api/v1/users/me/email-preferences/unsubscribe-all";
                String mailto   = "mailto:" + fromAddress + "?subject=unsubscribe";
                message.setHeader("List-Unsubscribe", "<" + unsubUrl + ">, <" + mailto + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

                mailSender.send(message);
                if (attempt > 1) {
                    log.info("[EMAIL] sent '{}' to {} on attempt {} (msgId={})",
                            subject, toAddress, attempt, entityRef);
                } else {
                    log.debug("[EMAIL] sent '{}' to {} (msgId={})", subject, toAddress, entityRef);
                }
                return;
            } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
                if (attempt == RETRY_ATTEMPTS) {
                    log.error("[EMAIL] gave up after {} attempts — to={} subject='{}': {}",
                            RETRY_ATTEMPTS, toAddress, subject, ex.getMessage());
                    return;
                }
                log.warn("[EMAIL] attempt {} failed for {} — retrying in {}ms: {}",
                        attempt, toAddress, backoffMs, ex.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs *= 2;
            } catch (Exception ex) {
                log.error("[EMAIL] unexpected error sending to {}: {}", toAddress, ex.getMessage(), ex);
                return;
            }
        }
    }

    /**
     * Resend send path with the same retry / back-off behaviour as the SMTP
     * path. Resend's own client doesn't retry — we wrap each call to handle
     * 429 / 5xx blips without dropping the email outright.
     */
    private void sendViaResend(String toAddress, String subject, String plainBody, String htmlBody) {
        long backoffMs = 500;
        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            String id = resendSender.send(toAddress, subject, plainBody, htmlBody);
            if (id != null) {
                if (attempt > 1) {
                    log.info("[EMAIL] sent via Resend '{}' to {} on attempt {} (id={})",
                            subject, toAddress, attempt, id);
                } else {
                    log.debug("[EMAIL] sent via Resend '{}' to {} (id={})", subject, toAddress, id);
                }
                return;
            }
            if (attempt == RETRY_ATTEMPTS) {
                log.error("[EMAIL] Resend gave up after {} attempts — to={} subject='{}'",
                        RETRY_ATTEMPTS, toAddress, subject);
                return;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs *= 2;
        }
    }
}
