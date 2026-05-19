package ak.dev.irc.app.common.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds subject + plain + HTML for each {@link NotificationKind} email.
 *
 * Domain-neutral — sits in {@code app.common.notification} alongside the
 * kind catalog so it can format emails for posts, research, Q&amp;A, and
 * system messages without depending on any of those domains.
 *
 * Deliberately simple — one method, switch on the resourceType for the
 * CTA link. Templating engines would buy us nothing here since messages
 * are short and consistent.
 */
@Component
public class NotificationEmailFormatter {

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    public record EmailBody(String subject, String plainText, String html) {}

    /**
     * @param kind         the kind being delivered (used for future per-kind tweaks)
     * @param title        the notification title (already actor-personalised)
     * @param body         the notification body (already actor-personalised)
     * @param resourceType e.g. "Post", "Comment", "Research" — used for CTA link routing
     * @param resourceId   id of the resource being notified about
     */
    public EmailBody format(NotificationKind kind, String title, String body,
                            String resourceType, UUID resourceId) {

        String subject = subjectFor(kind, title);
        String cta     = ctaLink(resourceType, resourceId);
        String plain   = body + (cta == null ? "" : "\n\nOpen: " + cta);
        String html    = renderHtml(subject, body, cta);
        return new EmailBody(subject, plain, html);
    }

    private String subjectFor(NotificationKind kind, String title) {
        // The title we generate is already concise ("Alice liked your post") —
        // emit it as the subject so inbox previews stay informative.
        return title;
    }

    private String ctaLink(String resourceType, UUID resourceId) {
        if (frontendUrl == null || frontendUrl.isBlank() || resourceId == null) return null;
        String base = frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/";
        return switch (resourceType == null ? "" : resourceType) {
            case "Post"     -> base + "p/"  + resourceId;
            case "Comment"  -> base + "c/"  + resourceId;
            case "Research" -> base + "r/"  + resourceId;
            case "Question" -> base + "q/"  + resourceId;
            case "Answer"   -> base + "a/"  + resourceId;
            case "Story"    -> base + "s/"  + resourceId;
            case "User"     -> base + "u/"  + resourceId;
            default          -> base;
        };
    }

    private String renderHtml(String subject, String body, String cta) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;")
          .append("max-width:520px;margin:0 auto;padding:24px;line-height:1.5;color:#111\">");
        sb.append("<h2 style=\"margin:0 0 16px;font-size:20px;font-weight:600\">").append(escape(subject)).append("</h2>");
        sb.append("<p style=\"margin:0 0 24px;font-size:15px\">").append(escape(body)).append("</p>");
        if (cta != null) {
            sb.append("<a href=\"").append(cta).append("\" ")
              .append("style=\"display:inline-block;padding:10px 20px;background:#1d4ed8;color:#fff;")
              .append("text-decoration:none;border-radius:8px;font-weight:500;font-size:14px\">")
              .append("Open in IRC")
              .append("</a>");
        }
        sb.append("<p style=\"margin:32px 0 0;color:#666;font-size:12px\">")
          .append("You're getting this because of your IRC notification settings.")
          .append("</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
