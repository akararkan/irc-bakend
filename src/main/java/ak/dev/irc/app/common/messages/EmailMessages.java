package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the email module ({@code app/email}):
 * error copy, the self-test email, and the per-{@code NotificationType}
 * action verbs / CTA button labels rendered by {@code EmailTemplate}.
 * Catalog: docs/errors/user-facing-messages.md §1 `email` rows.
 * Conventions: see {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class EmailMessages {

    private EmailMessages() {}

    // ── message text ─────────────────────────────────────────────────────
    /**
     * 401 on the email-preferences endpoints (code {@code AUTH_UNAUTHORIZED}
     * via the 1-arg UnauthorizedException).
     */
    public static final String AUTH_UNAUTHORIZED_MSG = "Authentication required";

    // ── response notes (200 bodies) ──────────────────────────────────────
    /** {@code reason} field of the {@code {queued:false}} self-test response. */
    public static final String NOTE_NO_EMAIL_ON_ACCOUNT = "no email on account";

    // ── self-test email (templates render with .formatted) ───────────────
    public static final String NOTIF_TEST_EMAIL_SUBJECT =
            "IRC Platform — test email";
    /** Plain-text self-test body; %s = recipient full name. */
    public static final String NOTIF_TEST_EMAIL_PLAIN =
            "Hi %s,\n\n"
                    + "This is a test email from IRC Platform. If you can read it, "
                    + "your notification pipeline is working end to end.\n\n"
                    + "Tip: if you don't see future activity emails, check your spam folder "
                    + "and mark this address as 'Not spam'.\n\n— IRC";
    /** HTML self-test body; %s = recipient full name. */
    public static final String NOTIF_TEST_EMAIL_HTML =
            "<p>Hi %s,</p>"
                    + "<p>This is a <strong>test email</strong> from IRC Platform. "
                    + "If you can read it, your notification pipeline is working end to end.</p>"
                    + "<p style=\"color:#57606a;font-size:13px;\">Tip — if you don't see future "
                    + "activity emails, check your spam folder and mark this address as "
                    + "\"Not spam\".</p><p>— IRC</p>";

    // ── EmailTemplate action verbs (one constant per switch arm, named
    //     after the arm's NotificationType; grouped arms — e.g.
    //     POST_MENTIONED/USER_MENTIONED — use the first type's name) ──────
    public static final String NOTIF_VERB_DEFAULT                     = "sent you an update about";

    public static final String NOTIF_VERB_NEW_FOLLOWER                = "started following you";
    public static final String NOTIF_VERB_UNFOLLOWED                  = "unfollowed you";
    public static final String NOTIF_VERB_BLOCKED                     = "blocked you";
    public static final String NOTIF_VERB_UNBLOCKED                   = "unblocked you";
    public static final String NOTIF_VERB_RESTRICTED                  = "restricted your account";
    public static final String NOTIF_VERB_CONNECTION_REQUEST          = "sent you a connection request";
    public static final String NOTIF_VERB_CONNECTION_ACCEPTED         = "accepted your connection request";

    public static final String NOTIF_VERB_POST_NEW                    = "published a new post";
    public static final String NOTIF_VERB_POST_REACTED                = "reacted to your post";
    public static final String NOTIF_VERB_POST_COMMENTED              = "commented on your post";
    public static final String NOTIF_VERB_POST_COMMENT_REPLIED        = "replied to your comment";
    public static final String NOTIF_VERB_POST_COMMENT_REACTED        = "reacted to your comment";
    public static final String NOTIF_VERB_POST_SHARED                 = "shared your post";
    public static final String NOTIF_VERB_POST_MENTIONED              = "mentioned you";

    public static final String NOTIF_VERB_PUBLICATION_LIKED           = "reacted to your research";
    public static final String NOTIF_VERB_PUBLICATION_COMMENTED       = "commented on your research";
    public static final String NOTIF_VERB_PUBLICATION_COMMENT_REACTED = "reacted to your comment on a research";
    public static final String NOTIF_VERB_PUBLICATION_CITED           = "cited your research";
    public static final String NOTIF_VERB_RESEARCH_CONTRIBUTOR_ADDED  = "added you as a contributor on their research";

    public static final String NOTIF_VERB_QUESTION_NEW                = "posted a new question";
    public static final String NOTIF_VERB_QUESTION_ANSWERED           = "answered your question";
    public static final String NOTIF_VERB_ANSWER_REPLIED              = "replied to your answer";
    public static final String NOTIF_VERB_ANSWER_REACTED              = "reacted to your answer";
    public static final String NOTIF_VERB_ANSWER_ACCEPTED             = "marked your answer as the best answer";

    public static final String NOTIF_VERB_SYSTEM_MESSAGE              = "sent you a system message";
    public static final String NOTIF_VERB_ACCOUNT_WARNING             = "issued an account warning";

    public static final String NOTIF_VERB_NEW_MESSAGE                 = "sent you a message";
    public static final String NOTIF_VERB_MESSAGE_REQUEST             = "wants to send you a message";
    public static final String NOTIF_VERB_ADDED_TO_GROUP              = "added you to a group";
    public static final String NOTIF_VERB_CALL_MISSED                 = "tried to call you";
    public static final String NOTIF_VERB_MESSAGE_MENTION             = "mentioned you in a chat";
    public static final String NOTIF_VERB_CHANNEL_NEW_POST            = "posted in a channel you follow";
    public static final String NOTIF_VERB_CHANNEL_JOIN_REQUEST        = "requested to join your channel";
    public static final String NOTIF_VERB_CHANNEL_JOIN_APPROVED       = "approved your channel join request";
    public static final String NOTIF_VERB_STREAM_STARTED              = "went live";

    public static final String NOTIF_VERB_STORY_PUBLISHED             = "published a new story";
    public static final String NOTIF_VERB_STORY_REACTED               = "reacted to your story";
    public static final String NOTIF_VERB_STORY_REPLIED               = "replied to your story";
    public static final String NOTIF_VERB_SOUND_APPROVED              = "approved your uploaded sound";
    public static final String NOTIF_VERB_SOUND_REJECTED              = "reviewed your uploaded sound";

    public static final String NOTIF_VERB_TRENDING_DIGEST             = "shared today's trending in scholarship";

    public static final String NOTIF_VERB_ADMIN_ANOMALY               = "flagged a metric anomaly";

    // ── EmailTemplate CTA button labels (same naming scheme) ─────────────
    public static final String NOTIF_CTA_DEFAULT          = "Open on IRC";
    public static final String NOTIF_CTA_NEW_FOLLOWER     = "View profile";
    public static final String NOTIF_CTA_POST_NEW         = "Read the post";
    public static final String NOTIF_CTA_POST_REACTED     = "Open post";
    public static final String NOTIF_CTA_PUBLICATION_LIKED = "Open research";
    public static final String NOTIF_CTA_QUESTION_NEW     = "Open question";
    public static final String NOTIF_CTA_SYSTEM_MESSAGE   = "View details";
    public static final String NOTIF_CTA_TRENDING_DIGEST  = "Explore trending tags";
}
