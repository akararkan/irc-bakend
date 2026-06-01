package ak.dev.irc.app.qna.realtime;

/**
 * Every event a Q&A realtime subscriber can receive on the per-question
 * channel. Mirrors {@code PostRealtimeEventType} so clients can reuse the
 * same SSE dispatch pattern.
 */
public enum QnaRealtimeEventType {

    // ── answers / reanswers ────────────────────────────────────────────
    ANSWER_CREATED,
    REANSWER_CREATED,
    ANSWER_EDITED,
    ANSWER_DELETED,

    // ── answer reactions (apply to top-level answers AND reanswers) ────
    ANSWER_REACTION_ADDED,
    ANSWER_REACTION_CHANGED,
    ANSWER_REACTION_REMOVED,

    // ── accept / unaccept (question author only) ───────────────────────
    ANSWER_ACCEPTED,
    ANSWER_UNACCEPTED,

    // ── question lifecycle ─────────────────────────────────────────────
    QUESTION_UPDATED,
    QUESTION_DELETED,
    QUESTION_LOCKED,
    QUESTION_UNLOCKED,

    // ── live counters (one event per counter so clients can patch
    //     individual numbers without re-fetching the whole question) ────
    VIEW_COUNT_UPDATED,
    SAVE_COUNT_UPDATED,
    SHARE_COUNT_UPDATED
}
