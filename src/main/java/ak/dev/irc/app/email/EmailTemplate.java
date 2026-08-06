package ak.dev.irc.app.email;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders the HTML + plain-text body for a notification email.
 *
 * <p>Self-contained, table-based HTML — renders cleanly in Gmail, Outlook,
 * Apple Mail and the major webmail clients. Each email describes the action
 * fully: who did it, what they did, the resource it affected, when it
 * happened, and how to open it.</p>
 */
@Component
@RequiredArgsConstructor
public class EmailTemplate {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.ENGLISH);

    /**
     * Public-facing frontend URL. CTA buttons in every email use this — the
     * backend's {@code irc.base-url} is never used here because the backend
     * has no user-facing pages to land on.
     */
    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Value("${irc.email.from-name:IRC Platform}")
    private String brandName;

    public Rendered render(NotificationResponse n, String recipientName) {

        // ── Personalisation ──────────────────────────────────────────────
        String greeting = recipientName == null || recipientName.isBlank()
                ? "Hi there,"
                : "Hi " + escape(firstName(recipientName)) + ",";

        // ── Action sentence — what happened, in plain English ────────────
        String actionVerb = actionVerb(n.type(), n.aggregateCount());
        String actorLabel = actorLabel(n);
        String resourceLabel = resourceLabel(n.type());

        // ── Headline (subject + h2) ──────────────────────────────────────
        String headline = n.title() == null || n.title().isBlank()
                ? buildHeadline(actorLabel, actionVerb, resourceLabel)
                : escape(n.title());

        // ── Aggregate badge ──────────────────────────────────────────────
        String aggregateBadge = n.aggregateCount() > 1
                ? renderBadge("+" + (n.aggregateCount() - 1) + " more")
                : "";

        // ── Detail body (the notification's preview / excerpt) ──────────
        String detail = n.body() == null ? "" : escape(n.body());

        // ── Actor block (avatar + name + handle) ─────────────────────────
        String actorBlock = renderActorBlock(n);

        // ── Context block (action sentence + resource type + timestamp) ─
        String contextBlock = renderContextBlock(actorLabel, actionVerb, resourceLabel,
                n.aggregateCount(), n.createdAt());

        // ── CTA — full URL to the resource ───────────────────────────────
        String ctaHref = buildDeepLink(n.deepLink());
        String ctaLabel = ctaLabel(n.type());
        String ctaBlock = ctaHref == null ? "" : renderCta(ctaHref, ctaLabel);

        // ── Outer HTML ───────────────────────────────────────────────────
        String html = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>%s</title>
            </head>
            <body style="margin:0;padding:0;background:#f6f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#0d1117;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f6f8fa;padding:32px 0;">
                <tr><td align="center">
                  <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border:1px solid #d8dee4;border-radius:10px;
                                max-width:600px;margin:0 16px;overflow:hidden;">

                    <!-- Brand bar -->
                    <tr><td style="padding:18px 32px;background:#0d1117;color:#ffffff;">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr>
                        <td style="font-size:14px;font-weight:600;letter-spacing:0.4px;">%s</td>
                        <td align="right" style="font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:1px;">Notification</td>
                      </tr></table>
                    </td></tr>

                    <!-- Headline -->
                    <tr><td style="padding:28px 32px 4px;">
                      <p style="margin:0 0 6px;color:#57606a;font-size:14px;">%s</p>
                      <h1 style="margin:0;font-size:20px;font-weight:600;line-height:1.35;color:#0d1117;">%s %s</h1>
                    </td></tr>

                    <!-- Actor block -->
                    %s

                    <!-- Context block (what + when) -->
                    %s

                    <!-- Detail / excerpt -->
                    %s

                    <!-- CTA -->
                    %s

                    <!-- Footer -->
                    <tr><td style="padding:20px 32px 28px;border-top:1px solid #eaeef2;background:#fafbfc;">
                      <p style="margin:0 0 6px;color:#57606a;font-size:12px;line-height:1.55;">
                        You're receiving this because you have email notifications enabled for <strong>%s</strong> on %s.
                      </p>
                      <p style="margin:0;color:#8b949e;font-size:11px;line-height:1.5;">
                        Manage notification preferences in your account settings · This is an automated message — please do not reply.
                      </p>
                    </td></tr>

                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(
                escape(headline),
                escape(brandName),
                greeting,
                escape(headline),
                aggregateBadge,
                actorBlock,
                contextBlock,
                detail.isEmpty() ? "" : renderDetail(detail),
                ctaBlock,
                escape(categoryLabel(n.type())),
                escape(brandName)
        );

        // ── Plain-text fallback (for clients that block HTML) ────────────
        StringBuilder plain = new StringBuilder();
        plain.append(headline).append('\n');
        plain.append("─".repeat(Math.min(60, headline.length()))).append('\n');
        plain.append(actorLabel).append(' ').append(actionVerb).append(' ').append(resourceLabel);
        if (n.aggregateCount() > 1) {
            plain.append(" (and ").append(n.aggregateCount() - 1).append(" other update")
                 .append(n.aggregateCount() - 1 > 1 ? "s" : "").append(')');
        }
        plain.append('\n');
        if (n.createdAt() != null) {
            plain.append("When: ").append(formatWhen(n.createdAt())).append('\n');
        }
        if (!detail.isEmpty()) {
            plain.append('\n').append(n.body()).append('\n');
        }
        if (ctaHref != null) {
            plain.append('\n').append(ctaLabel).append(":\n").append(ctaHref).append('\n');
        }
        plain.append("\n— ").append(brandName);

        // ── Subject line — actionable, scannable in inbox preview ────────
        String subject = buildSubject(actorLabel, actionVerb, resourceLabel, n.aggregateCount());

        return new Rendered(subject, plain.toString(), html);
    }

    // ── Block renderers ───────────────────────────────────────────────────

    private String renderActorBlock(NotificationResponse n) {
        if (n.actorFullName() == null && n.actorUsername() == null) return "";

        String name   = escape(coalesce(n.actorFullName(), n.actorUsername(), "Someone"));
        String handle = n.actorUsername() == null ? "" : "@" + escape(n.actorUsername());
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);

        String avatar;
        if (n.actorProfileImage() != null && !n.actorProfileImage().isBlank()) {
            avatar = """
                <img src="%s" width="44" height="44" alt=""
                     style="display:block;border-radius:50%%;border:1px solid #d8dee4;object-fit:cover;"/>
                """.formatted(escape(n.actorProfileImage()));
        } else {
            avatar = """
                <div style="width:44px;height:44px;border-radius:50%%;background:#1f6feb;color:#ffffff;
                            font-size:18px;font-weight:600;line-height:44px;text-align:center;">%s</div>
                """.formatted(escape(initial));
        }

        return """
            <tr><td style="padding:20px 32px 0;">
              <table role="presentation" cellpadding="0" cellspacing="0">
                <tr>
                  <td style="padding-right:12px;vertical-align:middle;">%s</td>
                  <td style="vertical-align:middle;">
                    <div style="font-size:15px;font-weight:600;color:#0d1117;line-height:1.2;">%s</div>
                    <div style="font-size:13px;color:#57606a;line-height:1.4;margin-top:2px;">%s</div>
                  </td>
                </tr>
              </table>
            </td></tr>
            """.formatted(avatar, name, handle);
    }

    private String renderContextBlock(String actor, String verb, String resource,
                                      long aggregate, LocalDateTime when) {
        StringBuilder sentence = new StringBuilder();
        sentence.append("<strong>").append(escape(actor)).append("</strong> ")
                .append(escape(verb)).append(' ').append(escape(resource));
        if (aggregate > 1) {
            sentence.append(" <span style=\"color:#57606a;\">— and ")
                    .append(aggregate - 1).append(" other update")
                    .append(aggregate - 1 > 1 ? "s" : "").append("</span>");
        }
        String whenLine = when == null ? "" :
                "<div style=\"margin-top:6px;color:#8b949e;font-size:12px;\">"
                        + escape(formatWhen(when)) + "</div>";

        return """
            <tr><td style="padding:14px 32px 0;">
              <div style="background:#f6f8fa;border:1px solid #eaeef2;border-radius:8px;
                          padding:14px 16px;font-size:14px;line-height:1.5;color:#24292f;">
                %s
                %s
              </div>
            </td></tr>
            """.formatted(sentence.toString(), whenLine);
    }

    private String renderDetail(String detail) {
        return """
            <tr><td style="padding:16px 32px 0;">
              <blockquote style="margin:0;padding:12px 16px;border-left:3px solid #1f6feb;
                                 background:#f6f8fa;color:#24292f;font-size:14px;line-height:1.55;
                                 border-radius:0 6px 6px 0;font-style:normal;">
                %s
              </blockquote>
            </td></tr>
            """.formatted(detail);
    }

    private String renderCta(String href, String label) {
        return """
            <tr><td align="center" style="padding:28px 32px 8px;">
              <a href="%s" style="background:#1f6feb;color:#ffffff;text-decoration:none;
                 padding:13px 32px;border-radius:6px;font-weight:600;font-size:15px;display:inline-block;
                 font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                %s →
              </a>
            </td></tr>
            <tr><td align="center" style="padding:0 32px 8px;">
              <p style="margin:0;color:#8b949e;font-size:11px;word-break:break-all;">
                Or open this link: <a href="%s" style="color:#1f6feb;text-decoration:none;">%s</a>
              </p>
            </td></tr>
            """.formatted(href, escape(label), href, escape(href));
    }

    private String renderBadge(String text) {
        return "<span style=\"display:inline-block;background:#1f6feb;color:#ffffff;font-size:11px;"
                + "font-weight:600;padding:2px 8px;border-radius:10px;margin-left:6px;vertical-align:middle;\">"
                + escape(text) + "</span>";
    }

    // ── Copy helpers ──────────────────────────────────────────────────────

    private String actionVerb(NotificationType type, long aggregate) {
        if (type == null) return "sent you an update about";
        boolean many = aggregate > 1;
        return switch (type) {
            case NEW_FOLLOWER         -> many ? "started following you" : "started following you";
            case UNFOLLOWED           -> "unfollowed you";
            case BLOCKED              -> "blocked you";
            case UNBLOCKED            -> "unblocked you";
            case RESTRICTED           -> "restricted your account";
            case CONNECTION_REQUEST   -> "sent you a connection request";
            case CONNECTION_ACCEPTED  -> "accepted your connection request";

            case POST_NEW             -> "published a new post";
            case POST_REACTED         -> many ? "reacted to your post" : "reacted to your post";
            case POST_COMMENTED       -> "commented on your post";
            case POST_COMMENT_REPLIED -> "replied to your comment";
            case POST_COMMENT_REACTED -> "reacted to your comment";
            case POST_SHARED          -> "shared your post";
            case POST_MENTIONED, USER_MENTIONED -> "mentioned you";

            case PUBLICATION_LIKED            -> "reacted to your research";
            case PUBLICATION_COMMENTED        -> "commented on your research";
            case PUBLICATION_COMMENT_REACTED  -> "reacted to your comment on a research";
            case PUBLICATION_CITED            -> "cited your research";
            case RESEARCH_CONTRIBUTOR_ADDED   -> "added you as a contributor on their research";

            case QUESTION_NEW              -> "posted a new question";
            case QUESTION_ANSWERED         -> "answered your question";
            case ANSWER_REPLIED            -> "replied to your answer";
            case ANSWER_REACTED            -> "reacted to your answer";
            case ANSWER_ACCEPTED           -> "marked your answer as the best answer";

            case SYSTEM_MESSAGE, SYSTEM_ANNOUNCEMENT -> "sent you a system message";
            case ACCOUNT_WARNING                     -> "issued an account warning";

            // Chat kinds are in-app only (never emailed) but must be covered here.
            case NEW_MESSAGE      -> "sent you a message";
            case MESSAGE_REQUEST  -> "wants to send you a message";
            case ADDED_TO_GROUP   -> "added you to a group";
            case CALL_MISSED      -> "tried to call you";
            case MESSAGE_MENTION  -> "mentioned you in a chat";
            case CHANNEL_NEW_POST      -> "posted in a channel you follow";
            case CHANNEL_JOIN_REQUEST  -> "requested to join your channel";
            case CHANNEL_JOIN_APPROVED -> "approved your channel join request";
            case STREAM_STARTED        -> "went live";

            case STORY_PUBLISHED  -> "published a new story";
            case STORY_REACTED    -> "reacted to your story";
            case STORY_REPLIED    -> "replied to your story";
            case SOUND_APPROVED   -> "approved your uploaded sound";
            case SOUND_REJECTED   -> "reviewed your uploaded sound";

            case TRENDING_DIGEST  -> "shared today's trending in scholarship";

            case ADMIN_ANOMALY    -> "flagged a metric anomaly";
        };
    }

    private String resourceLabel(NotificationType type) {
        if (type == null) return "";
        return switch (type) {
            case POST_NEW, POST_REACTED, POST_COMMENTED,
                 POST_COMMENT_REPLIED, POST_COMMENT_REACTED, POST_SHARED -> "on IRC";
            case PUBLICATION_LIKED, PUBLICATION_COMMENTED,
                 PUBLICATION_COMMENT_REACTED, PUBLICATION_CITED,
                 RESEARCH_CONTRIBUTOR_ADDED -> "in your research";
            case QUESTION_NEW, QUESTION_ANSWERED, ANSWER_REPLIED,
                 ANSWER_REACTED, ANSWER_ACCEPTED -> "in Q&A";
            default -> "";
        };
    }

    private String ctaLabel(NotificationType type) {
        if (type == null) return "Open on IRC";
        return switch (type) {
            case NEW_FOLLOWER, CONNECTION_REQUEST, CONNECTION_ACCEPTED -> "View profile";
            case POST_NEW                                              -> "Read the post";
            case POST_REACTED, POST_COMMENTED, POST_COMMENT_REPLIED,
                 POST_COMMENT_REACTED, POST_SHARED,
                 POST_MENTIONED, USER_MENTIONED                        -> "Open post";
            case PUBLICATION_LIKED, PUBLICATION_COMMENTED,
                 PUBLICATION_COMMENT_REACTED, PUBLICATION_CITED        -> "Open research";
            case QUESTION_NEW, QUESTION_ANSWERED, ANSWER_REPLIED,
                 ANSWER_REACTED, ANSWER_ACCEPTED                       -> "Open question";
            case SYSTEM_MESSAGE, SYSTEM_ANNOUNCEMENT, ACCOUNT_WARNING  -> "View details";
            case TRENDING_DIGEST                                       -> "Explore trending tags";
            default                                                    -> "Open on IRC";
        };
    }

    private String categoryLabel(NotificationType type) {
        if (type == null) return "activity";
        return switch (type) {
            case POST_NEW, POST_REACTED, POST_COMMENTED, POST_COMMENT_REPLIED,
                 POST_COMMENT_REACTED, POST_SHARED -> "post activity";
            case PUBLICATION_LIKED, PUBLICATION_COMMENTED,
                 PUBLICATION_COMMENT_REACTED, PUBLICATION_CITED -> "research activity";
            case QUESTION_NEW, QUESTION_ANSWERED, ANSWER_REPLIED,
                 ANSWER_REACTED, ANSWER_ACCEPTED -> "Q&A activity";
            case NEW_FOLLOWER, UNFOLLOWED, BLOCKED, UNBLOCKED,
                 CONNECTION_REQUEST, CONNECTION_ACCEPTED -> "social activity";
            case POST_MENTIONED, USER_MENTIONED -> "mentions";
            case TRENDING_DIGEST                -> "trending digest";
            default -> "system messages";
        };
    }

    // ── String + URL helpers ─────────────────────────────────────────────

    private String actorLabel(NotificationResponse n) {
        if (n.actorFullName() != null && !n.actorFullName().isBlank()) return n.actorFullName();
        if (n.actorUsername() != null && !n.actorUsername().isBlank()) return "@" + n.actorUsername();
        return "Someone";
    }

    private String buildHeadline(String actor, String verb, String resource) {
        return resource.isEmpty()
                ? actor + " " + verb
                : actor + " " + verb + " " + resource;
    }

    private String buildSubject(String actor, String verb, String resource, long aggregate) {
        String base = buildHeadline(actor, verb, resource);
        if (aggregate > 1) base += " (+" + (aggregate - 1) + " more)";
        return base;
    }

    /**
     * Builds the absolute URL the email CTA opens. Always uses the frontend
     * URL — never the backend's {@code irc.base-url}, since the backend has
     * no user-facing pages and {@code https://api.../posts/{id}} would 404.
     *
     * <p>Returns {@code null} when {@code app.frontend-url} is not configured
     * — the caller then renders the email without a CTA button rather than
     * pointing the user to a broken link.</p>
     */
    private String buildDeepLink(String deepLink) {
        if (deepLink == null || deepLink.isBlank()) return null;
        if (frontendUrl == null || frontendUrl.isBlank()) {
            // Loud — once per email send is fine; this is a misconfiguration
            // the operator must fix.
            return null;
        }
        String base = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
        return deepLink.startsWith("/") ? base + deepLink : base + "/" + deepLink;
    }

    private String formatWhen(LocalDateTime when) {
        return WHEN.format(when.atZone(ZoneId.systemDefault()).toLocalDateTime()) + " UTC";
    }

    private String firstName(String full) {
        int sp = full.indexOf(' ');
        return sp > 0 ? full.substring(0, sp) : full;
    }

    private String coalesce(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return "";
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
