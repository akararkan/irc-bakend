package ak.dev.irc.app.email;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders the HTML + plain-text body for a notification email.
 *
 * <p>Kept inline (no external template engine dep) so we avoid the cost of a
 * Thymeleaf / Freemarker pull-in for what is fundamentally a simple,
 * brand-controlled snippet. The output is a self-contained, table-based HTML
 * email that renders cleanly in Gmail, Outlook, Apple Mail, and the major
 * webmail clients.</p>
 */
@Component
@RequiredArgsConstructor
public class EmailTemplate {

    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    @Value("${irc.email.from-name:IRC Platform}")
    private String brandName;

    public Rendered render(NotificationResponse n, String recipientName) {
        String greeting = recipientName == null || recipientName.isBlank()
                ? "Hi there,"
                : "Hi " + escape(recipientName) + ",";

        String headline = n.title() == null ? "You have a new notification" : escape(n.title());
        String body     = n.body()  == null ? ""                            : escape(n.body());
        String count    = n.aggregateCount() > 1
                ? " (" + n.aggregateCount() + " similar updates)"
                : "";

        String cta = "";
        if (n.deepLink() != null && !n.deepLink().isBlank()) {
            String href = baseUrl + n.deepLink();
            cta = """
                <tr><td align="center" style="padding:24px 0 8px;">
                  <a href="%s" style="background:#1f6feb;color:#fff;text-decoration:none;
                     padding:12px 28px;border-radius:6px;font-weight:600;display:inline-block;
                     font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                    Open on %s
                  </a>
                </td></tr>
                """.formatted(href, escape(brandName));
        }

        String html = """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/></head>
            <body style="margin:0;padding:0;background:#f6f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f6f8fa;padding:32px 0;">
                <tr><td align="center">
                  <table role="presentation" width="560" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.06);
                                max-width:560px;margin:0 16px;">
                    <tr><td style="padding:24px 32px 8px;">
                      <h2 style="margin:0;font-size:18px;font-weight:600;color:#0d1117;">%s</h2>
                    </td></tr>
                    <tr><td style="padding:0 32px;">
                      <p style="margin:8px 0 0;color:#57606a;font-size:15px;line-height:1.5;">%s</p>
                      <p style="margin:12px 0 0;font-size:16px;line-height:1.5;color:#0d1117;">%s%s</p>
                    </td></tr>
                    %s
                    <tr><td style="padding:24px 32px;border-top:1px solid #eaeef2;">
                      <p style="margin:0;color:#8b949e;font-size:12px;line-height:1.5;">
                        You're receiving this because you have email notifications enabled on %s.<br/>
                        Manage your preferences in your account settings.
                      </p>
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(escape(brandName), greeting, headline, count, cta, escape(brandName));

        StringBuilder plain = new StringBuilder();
        plain.append(headline).append(count).append('\n');
        if (!body.isEmpty()) plain.append(n.body()).append('\n');
        if (n.deepLink() != null && !n.deepLink().isBlank()) {
            plain.append('\n').append(baseUrl).append(n.deepLink()).append('\n');
        }
        plain.append("\n— ").append(brandName);

        // Subject is short and actionable — falls back to the title if the
        // notification's title is unusable.
        String subject = headline + (n.aggregateCount() > 1
                ? " — " + n.aggregateCount() + " updates"
                : "");

        return new Rendered(subject, plain.toString(), html);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public record Rendered(String subject, String plainBody, String htmlBody) {}
}
