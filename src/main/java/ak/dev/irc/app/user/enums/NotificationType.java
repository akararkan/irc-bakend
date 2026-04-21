package ak.dev.irc.app.user.enums;

public enum NotificationType {
    NEW_FOLLOWER,
    UNFOLLOWED,
    BLOCKED,
    UNBLOCKED,
    RESTRICTED,
    CONNECTION_REQUEST,
    CONNECTION_ACCEPTED,
    PUBLICATION_LIKED,
    PUBLICATION_COMMENTED,
    PUBLICATION_CITED,
    SYSTEM_MESSAGE,

    // ── Post: lifecycle ──────────────────────────────────────
    POST_NEW,             // someone you follow published a post

    // ── Post: social interactions ────────────────────────────
    POST_REACTED,         // someone reacted to your post
    POST_COMMENTED,       // someone commented on your post
    POST_COMMENT_REPLIED, // someone replied to your comment
    POST_MENTIONED,       // someone mentioned you
    POST_COMMENT_REACTED, // someone reacted to your comment
    POST_SHARED,          // someone shared your post

    // ── Q&A ─────────────────────────────────────────────────
    QUESTION_NEW,
    QUESTION_ANSWERED,

    // ── System ───────────────────────────────────────────────
    SYSTEM_ANNOUNCEMENT,
    ACCOUNT_WARNING
}
