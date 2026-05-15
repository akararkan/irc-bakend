package ak.dev.irc.app.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resend HTTPS email transport.
 *
 * <p>Used when {@code RESEND_API_KEY} is set on the deployment. Required for
 * Railway / Heroku / Render / GCP / Fly.io / DigitalOcean App Platform —
 * all of which block outbound SMTP on ports 25 / 465 / 587. Resend speaks
 * HTTPS over port 443, which every provider allows.</p>
 *
 * <p>Falls back to a no-op when the key is empty so a missing env var never
 * blocks startup.</p>
 *
 * <p>This component is only registered when {@code irc.email.provider=resend}
 * (the default when {@code RESEND_API_KEY} is set — see
 * {@link EmailService#sendAsync}).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "irc.email", name = "provider", havingValue = "resend",
        matchIfMissing = false)
public class ResendEmailSender {

    @Value("${resend.api-key:}")
    private String apiKey;

    @Value("${irc.email.from-address}")
    private String defaultFromAddress;

    @Value("${irc.email.from-name:IRC Platform}")
    private String defaultFromName;

    @Value("${irc.email.reply-to:}")
    private String replyTo;

    private Resend client;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[RESEND] ⚠ RESEND_API_KEY is empty — Resend transport will no-op. "
                   + "Set RESEND_API_KEY on Railway → Variables to enable HTTPS email delivery.");
            return;
        }
        client = new Resend(apiKey);
        log.info("[RESEND] ✅ Initialised — from='{} <{}>'", defaultFromName, defaultFromAddress);
    }

    public boolean isReady() {
        return client != null;
    }

    /**
     * Send a single email through the Resend API.
     *
     * @return the Resend message id on success, {@code null} on failure
     *         (failures are logged with full context — never thrown).
     */
    public String send(String toAddress,
                       String subject,
                       String plainBody,
                       String htmlBody) {
        if (!isReady()) {
            log.warn("[RESEND] skipped — client not initialised (no API key). subject='{}'", subject);
            return null;
        }
        if (toAddress == null || toAddress.isBlank()) return null;

        try {
            String fromHeader = defaultFromName != null && !defaultFromName.isBlank()
                    ? defaultFromName + " <" + defaultFromAddress + ">"
                    : defaultFromAddress;

            CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                    .from(fromHeader)
                    .to(toAddress)
                    .subject(subject == null ? "" : subject)
                    .html(htmlBody == null || htmlBody.isBlank() ? wrapPlain(plainBody) : htmlBody)
                    .text(plainBody == null ? "" : plainBody);

            if (replyTo != null && !replyTo.isBlank()) {
                builder.replyTo(List.of(replyTo));
            }

            CreateEmailResponse resp = client.emails().send(builder.build());
            String id = resp != null ? resp.getId() : null;
            log.debug("[RESEND] sent '{}' to {} (id={})", subject, toAddress, id);
            return id;

        } catch (ResendException ex) {
            log.error("[RESEND] ❌ API rejected send to {} subject='{}': {}",
                    toAddress, subject, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.error("[RESEND] ❌ unexpected error sending to {} subject='{}': {}",
                    toAddress, subject, ex.getMessage(), ex);
            return null;
        }
    }

    private String wrapPlain(String plain) {
        if (plain == null || plain.isBlank()) return "";
        return "<pre style=\"font-family:-apple-system,sans-serif;white-space:pre-wrap;\">"
                + escape(plain) + "</pre>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
