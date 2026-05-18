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
    PUBLICATION_COMMENT_REACTED, // someone reacted to your comment on a research
    PUBLICATION_CITED,
    RESEARCH_CONTRIBUTOR_ADDED,  // a researcher added you as a contributor on their paper
    SYSTEM_MESSAGE,

    // ── Post: lifecycle ──────────────────────────────────────
    POST_NEW,             // someone you follow published a post

    // ── Post: social interactions ────────────────────────────
    POST_REACTED,         // someone reacted to your post
    POST_COMMENTED,       // someone commented on your post
    POST_COMMENT_REPLIED, // someone replied to your comment
    POST_MENTIONED,       // legacy — superseded by USER_MENTIONED
    USER_MENTIONED,       // someone mentioned you anywhere (post / comment / research / answer)
    POST_COMMENT_REACTED, // someone reacted to your comment
    POST_SHARED,          // someone shared your post

    // ── Q&A ─────────────────────────────────────────────────
    QUESTION_NEW,
    QUESTION_ANSWERED,
    ANSWER_REPLIED,
    ANSWER_REACTED,
    ANSWER_ACCEPTED,
    ANSWER_FEEDBACK_RECEIVED,

    // ── Story ────────────────────────────────────────────────
    STORY_PUBLISHED,      // someone you follow posted a new story
    STORY_REACTED,        // someone reacted to your story
    STORY_REPLIED,        // someone replied to your story
    SOUND_APPROVED,       // your uploaded sound was approved

    // ── System ───────────────────────────────────────────────
    SYSTEM_ANNOUNCEMENT,
    ACCOUNT_WARNING
}
