package ak.dev.irc.app.post.realtime;

public enum PostRealtimeEventType {
    REACTION_ADDED,
    REACTION_CHANGED,
    REACTION_REMOVED,

    COMMENT_CREATED,
    COMMENT_EDITED,
    COMMENT_DELETED,

    REPLY_CREATED,

    COMMENT_REACTION_ADDED,
    COMMENT_REACTION_CHANGED,
    COMMENT_REACTION_REMOVED,

    VIEW_COUNT_UPDATED,
    SHARE_COUNT_UPDATED,

    // ── post lifecycle (broadcast on the post's own stream) ───────────
    /** Post body / visibility / metadata was edited by the author. */
    POST_UPDATED,
    /** Post was soft-deleted by the author. Subscribers should close the view. */
    POST_DELETED
}
