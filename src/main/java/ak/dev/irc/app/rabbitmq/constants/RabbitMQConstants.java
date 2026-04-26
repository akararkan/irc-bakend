package ak.dev.irc.app.rabbitmq.constants;

/**
 * Central registry of all RabbitMQ exchange, queue, and routing-key names.
 * Keeping them here prevents typos and makes refactoring painless.
 */
public final class RabbitMQConstants {

    private RabbitMQConstants() {}

    // ── Exchanges ─────────────────────────────────────────────────────────────

    /** Main topic exchange — all IRC events flow through here */
    public static final String IRC_EXCHANGE     = "irc.topic.exchange";

    /** Dead-letter exchange — receives messages that fail after max retries */
    public static final String IRC_DLX_EXCHANGE = "irc.dlx.exchange";

    // ── Queues ────────────────────────────────────────────────────────────────

    /** Receives social + lifecycle events → creates Notification records */
    public static final String NOTIFICATION_QUEUE  = "irc.queue.notifications";

    /** Receives analytics events → persists views / downloads / counters */
    public static final String ANALYTICS_QUEUE     = "irc.queue.analytics";

    /** Parking lot for messages that exhausted their retry attempts */
    public static final String DEAD_LETTER_QUEUE   = "irc.queue.dead-letter";

    // ── Routing keys — User Social ────────────────────────────────────────────

    public static final String USER_FOLLOWED   = "user.social.followed";
    public static final String USER_UNFOLLOWED = "user.social.unfollowed";
    public static final String USER_BLOCKED    = "user.social.blocked";
    public static final String USER_UNBLOCKED  = "user.social.unblocked";

    // ── Routing keys — Research Lifecycle ─────────────────────────────────────

    public static final String RESEARCH_PUBLISHED = "research.lifecycle.published";

    // ── Routing keys — Research Social ────────────────────────────────────────

    public static final String RESEARCH_REACTED   = "research.social.reacted";
    public static final String RESEARCH_COMMENTED = "research.social.commented";

    // ── Routing keys — Research Analytics ────────────────────────────────────

    public static final String RESEARCH_VIEWED     = "research.analytics.viewed";
    public static final String RESEARCH_DOWNLOADED = "research.analytics.downloaded";

    // ── Routing keys — Q&A ───────────────────────────────────────────────────

    public static final String QNA_QUESTION_CREATED   = "qna.lifecycle.created";
    public static final String QNA_QUESTION_ANSWERED  = "qna.social.answered";
    public static final String QNA_ANSWER_ACCEPTED    = "qna.social.accepted";
    public static final String QNA_FEEDBACK_ADDED     = "qna.social.feedback";

    // ════════════════════════════════════════════════════════════
    //  Post / Social-media events  (NEW)
    // ════════════════════════════════════════════════════════════
    public static final String POST_CREATED          = "post.lifecycle.created";
    public static final String POST_REACTED          = "post.social.reacted";
    public static final String POST_COMMENTED        = "post.social.commented";
    public static final String POST_COMMENT_REACTED  = "post.social.comment.reacted";
    public static final String POST_SHARED           = "post.social.shared";

    // ── Binding patterns (wildcard) ───────────────────────────
    /** Catches post.lifecycle.* */
    public static final String POST_LIFECYCLE_PATTERN = "post.lifecycle.#";
    /** Catches post.social.* */
    public static final String POST_SOCIAL_PATTERN    = "post.social.#";

    /** Catches qna.lifecycle.* */
    public static final String QNA_LIFECYCLE_PATTERN = "qna.lifecycle.#";
    /** Catches qna.social.* */
    public static final String QNA_SOCIAL_PATTERN    = "qna.social.#";
}
