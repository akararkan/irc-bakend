package ak.dev.irc.app.activity.enums;

public enum UserActivityType {
    // ── Post creation + engagement ────────────────────────────────────────
    POST_CREATED,            // user published a post (any PostType incl. REEL)
    POST_REACTION,           // user liked a post
    POST_COMMENT,            // user commented on a post
    POST_COMMENT_REACTION,   // user liked a comment
    POST_SHARE,              // user shared a post
    POST_SAVED,              // user bookmarked a post
    REEL_WATCH,              // user watched a reel

    // ── Discovery ─────────────────────────────────────────────────────────
    GLOBAL_SEARCH,
    HASHTAG_SEARCH,
    MENTION_LOOKUP,          // outgoing: typed an @ and picked a user
    USER_MENTIONED,          // incoming: another user mentioned this user
    PROFILE_VIEW,
    FOLLOWED_USER,           // user started following another user

    // ── Q&A ──────────────────────────────────────────────────────────────
    QNA_QUESTION_CREATED,
    QNA_QUESTION_SAVED,      // user bookmarked a question
    QNA_ANSWER_CREATED,
    QNA_REANSWER_CREATED,
    QNA_ANSWER_REACTION,
    QNA_BEST_ANSWER_VOTE,
    QNA_ANSWER_FEEDBACK,

    // ── Research social actions (parity with POST_*) ─────────────────────
    RESEARCH_PUBLISHED,      // user published a research paper
    RESEARCH_SAVED,          // user bookmarked a research paper
    RESEARCH_REACTION,
    RESEARCH_COMMENT,
    RESEARCH_COMMENT_REACTION,

    // ── Story interactions ────────────────────────────────────────────────
    STORY_VIEWED,
    STORY_REACTED,
    STORY_REPLIED,
    STORY_POLL_VOTED,

    // ── Sound library ─────────────────────────────────────────────────────
    SOUND_USED
}
