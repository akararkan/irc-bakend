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

    // ── accept / unaccept best answer ──────────────────────────────────
    ANSWER_ACCEPTED,
    ANSWER_UNACCEPTED,

    // ── question-author feedback on answers ────────────────────────────
    ANSWER_FEEDBACK_ADDED,
    ANSWER_FEEDBACK_EDITED,
    ANSWER_FEEDBACK_DELETED,

    // ── question lifecycle ─────────────────────────────────────────────
    QUESTION_UPDATED,
    QUESTION_DELETED,
    QUESTION_LOCKED,
    QUESTION_UNLOCKED
}
