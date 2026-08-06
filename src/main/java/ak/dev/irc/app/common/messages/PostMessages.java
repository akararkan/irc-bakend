package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the post module ({@code app/post}).
 * Catalog: docs/errors/user-facing-messages.md §1 `post` (§1.49–§1.56) and
 * the notification-copy tables. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class PostMessages {

    private PostMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String NOT_OWNER          = "NOT_OWNER";
    /** Ad-hoc Map body code on multipart create — lowercase by legacy contract. */
    public static final String UPLOAD_FAILED      = "upload_failed";
    /** Ad-hoc Map body code on multipart create — lowercase by legacy contract. */
    public static final String POST_CREATE_FAILED = "post_create_failed";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String AUTH_REQUIRED_MSG =
            "Authentication required";
    public static final String AUTH_REQUIRED_SSE_MSG =
            "Authentication required. Pass access token as ?token=<jwt>.";
    public static final String ACCESS_FORBIDDEN_MSG =
            "You do not have permission to perform this action";
    public static final String ORDER_LIST_REQUIRED_MSG =
            "order list is required";
    public static final String POLL_VOTERS_AUTHOR_ONLY_MSG =
            "Only the poll's author can list voters";
    public static final String NOT_OWNER_MSG =
            "You can only read your own mentions.";
    public static final String NOT_AUTHOR_MSG =
            "Not the author";
    public static final String NOT_STORY_AUTHOR_MSG =
            "Not the story author";
    public static final String NOT_HIGHLIGHT_OWNER_MSG =
            "Not the highlight owner";
    public static final String NOT_POST_AUTHOR_MSG =
            "Not the post author";
    public static final String STORY_DELETE_AUTHOR_ONLY_MSG =
            "Only the author can delete this story";
    public static final String VIEWER_LOG_CHANNEL_ADMINS_ONLY_MSG =
            "Only channel admins can view the viewer log";
    public static final String VIEWER_LOG_AUTHOR_ONLY_MSG =
            "Only the story author can view the viewer log";
    public static final String COMMENT_NOT_FOUND_MSG =
            "Comment not found: %s";
    public static final String POLL_CHOICE_INVALID_MSG =
            "Choice must be A or B";

    // ── notification copy (in-app bell + notification SSE) ──────────────
    public static final String NOTIF_POST_COMMENTED_TITLE =
            "New comment on your post";
    public static final String NOTIF_POST_COMMENTED_BODY =
            "%s commented: \"%s\"";
    public static final String NOTIF_POST_COMMENT_REPLIED_TITLE =
            "Someone replied to your comment";
    public static final String NOTIF_POST_COMMENT_REPLIED_BODY =
            "%s replied: \"%s\"";
    public static final String NOTIF_POST_REACTED_TITLE =
            "Someone liked your post";
    public static final String NOTIF_POST_REACTED_BODY =
            "%s liked your post";
    public static final String NOTIF_POST_COMMENT_REACTED_TITLE =
            "Someone liked your comment";
    public static final String NOTIF_POST_COMMENT_REACTED_BODY =
            "%s liked your comment";
    public static final String NOTIF_POST_SHARED_TITLE =
            "Your post was shared";
    public static final String NOTIF_POST_SHARED_BODY =
            "%s shared your post";
    public static final String NOTIF_POST_NEW_TITLE =
            "%s posted";
    public static final String NOTIF_POST_NEW_BODY_FALLBACK =
            "%s just posted.";
    public static final String NOTIF_USER_MENTIONED_TITLE =
            "You were mentioned";
    public static final String NOTIF_USER_MENTIONED_BODY =
            "%s mentioned you: \"%s\"";
}
