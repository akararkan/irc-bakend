package ak.dev.irc.app.research.realtime;

/**
 * Every event a research-page realtime subscriber can receive on the
 * per-research channel. Mirrors the post and Q&A realtime patterns.
 */
public enum ResearchRealtimeEventType {

    // ── reactions ────────────────────────────────────────────────────
    REACTION_ADDED,
    REACTION_CHANGED,
    REACTION_REMOVED,

    // ── comments / replies ───────────────────────────────────────────
    COMMENT_CREATED,
    COMMENT_EDITED,
    COMMENT_DELETED,
    REPLY_CREATED,

    // ── comment reactions (multi-reaction — parity with PostRealtimeEventType.COMMENT_REACTION_*) ──
    COMMENT_REACTION_ADDED,
    COMMENT_REACTION_CHANGED,
    COMMENT_REACTION_REMOVED,

    // ── live counters (one event per counter so clients can patch
    //     individual numbers without re-fetching the whole research) ─
    VIEW_COUNT_UPDATED,
    DOWNLOAD_COUNT_UPDATED,
    SHARE_COUNT_UPDATED,
    SAVE_COUNT_UPDATED,
    CITATION_COUNT_UPDATED,
    REACTION_COUNT_UPDATED,
    COMMENT_COUNT_UPDATED,

    // ── lifecycle ────────────────────────────────────────────────────
    RESEARCH_UPDATED,
    RESEARCH_DELETED,
    RESEARCH_PUBLISHED
}
