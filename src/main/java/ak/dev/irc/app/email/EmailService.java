package ak.dev.irc.app.email;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Value("${irc.email.from-address}")
    private String fromAddress;

    @Value("${irc.email.from-name:IRC Platform}")
    private String fromName;

    @Value("${irc.email.enabled:true}")
    private boolean enabled;

    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    public boolean isEnabled() {
        return enabled
                && fromAddress != null
                && !fromAddress.isBlank();
    }

    @PostConstruct
    public void announceConfig() {
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
        log.info("[EMAIL] startup — enabled={} host={} port={} username={} from={} fromName={} passwordSet={}",
                isEnabled(), host, port, username, fromAddress, fromName,
                password != null && !password.isBlank());

        // Loud diagnostics — cover the three failures we keep seeing in production.
        if (host != null && host.contains("@")) {
            log.error("[EMAIL] ❌ SMTP host '{}' looks like an email address — set spring.mail.host "
                    + "(MAIL_HOST env var) to your SMTP server (e.g. smtp.gmail.com), not your account.",
                    host);
        }
        if (username == null || username.isBlank()) {
            log.error("[EMAIL] ❌ MAIL_USERNAME is empty — set it on Railway to the Gmail address that sends mail. "
                    + "No email will be delivered until this is set.");
        }
        if (password == null || password.isBlank()) {
            log.error("[EMAIL] ❌ MAIL_PASSWORD is empty — for Gmail you need a 16-char App Password "
                    + "(generate at https://myaccount.google.com/apppasswords AFTER turning on 2-Step "
                    + "Verification). Your normal Google password will NOT work over SMTP.");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            log.error("[EMAIL] ❌ irc.email.from-address is empty — set MAIL_FROM (or MAIL_USERNAME) on Railway.");
        }
        if (!isEnabled()) {
            log.warn("[EMAIL] ⚠ Email service disabled — no notification emails will be sent.");
            return;
        }

        // Try a real SMTP login at boot so failures are visible IMMEDIATELY in the
        // deployment logs instead of only when the first event tries to email out.
        if (mailSender instanceof JavaMailSenderImpl impl) {
            try {
                impl.testConnection();
                log.info("[EMAIL] ✅ SMTP connection test succeeded — host={} username={}", host, username);
            } catch (MessagingException ex) {
                log.error("[EMAIL] ❌ SMTP connection test FAILED — host={} port={} username={}: {}",
                        host, port, username, ex.getMessage());
                log.error("[EMAIL]    For Gmail this almost always means: (1) wrong app password, "
                        + "(2) 2-Step Verification not enabled on the sending account, or "
                        + "(3) the sending account has no app password generated yet.");
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
}
