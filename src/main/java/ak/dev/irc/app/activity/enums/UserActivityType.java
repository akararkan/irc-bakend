package ak.dev.irc.app.activity.enums;

public enum UserActivityType {
    POST_REACTION,
    POST_COMMENT,
    POST_COMMENT_REACTION,
    POST_SHARE,
    REEL_WATCH,
    GLOBAL_SEARCH,
    HASHTAG_SEARCH,
    MENTION_LOOKUP,
    PROFILE_VIEW,
    QNA_QUESTION_CREATED,
    QNA_ANSWER_CREATED,
    QNA_REANSWER_CREATED,
    QNA_ANSWER_REACTION,
    QNA_BEST_ANSWER_VOTE,
    QNA_ANSWER_FEEDBACK,

    // ── Research social actions (parity with POST_*) ─────────────────────
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
