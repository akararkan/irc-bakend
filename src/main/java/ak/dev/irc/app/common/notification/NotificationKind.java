package ak.dev.irc.app.common.notification;

/**
 * Cross-domain catalog of every notification kind the platform can produce.
 *
 * <p>This enum lives in {@code app.common.notification} rather than under
 * {@code app.post} because notifications span Post / Story / Research / QnA
 * / system events — no single domain owns it. Each kind carries three pieces
 * of metadata that the dispatcher reads to decide HOW to deliver it, so
 * adding a new kind requires only one new row here.</p>
 *
 * <h3>Metadata</h3>
 * <ul>
 *   <li><b>{@link PrefCategory prefCategory}</b> — which user-preference toggle
 *       gates the email for this kind:
 *       <ul>
 *         <li>{@code SOCIAL}   — engagement on your content (reactions, comments,
 *             shares, replies, follows). Gated by {@code user.emailSocialEnabled}.</li>
 *         <li>{@code MENTIONS} — direct {@code @username} mentions anywhere.
 *             Gated by {@code user.emailMentionsEnabled}.</li>
 *         <li>{@code SYSTEM}   — platform messages, moderation, account warnings.
 *             Gated by {@code user.emailSystemEnabled}.</li>
 *       </ul>
 *       The master toggle {@code user.emailNotificationsEnabled} sits above
 *       all three.</li>
 *
 *   <li><b>{@code aggregable}</b> — should N events of this kind to the same
 *       recipient in a 60-minute window coalesce into one inbox row? When
 *       true, {@code aggregate_count} bumps and {@code last_actor_id} replaces
 *       the prior actor so the client renders "Alice and 4 others …".</li>
 *
 *   <li><b>{@code emailEligible}</b> — does this kind EVER trigger an email?
 *       High-volume social events (likes, repeat likes) are intentionally
 *       in-app only — sending an email per like would be spam. Mentions /
 *       comments / shares / system messages do email.</li>
 * </ul>
 *
 * <h3>Aggregation group-key convention</h3>
 * <p>The {@code groupKey} on the {@code DeliverRequest} is what binds N
 * coalescable events into one row. Pattern across the codebase:
 * <pre>
 *   {KIND}:{resourceId}        — e.g. "POST_REACTED:abc-123"
 *   {KIND}:{parentId}          — e.g. "POST_COMMENT_REPLIED:cmt-xyz"
 *   {KIND}:{postId}:{userId}   — e.g. "USER_MENTIONED:p-1:u-42"
 * </pre>
 * Mismatched group keys naturally produce distinct inbox rows.</p>
 *
 * <h3>Post-package kinds (full reference)</h3>
 *
 * <table border="1" cellpadding="6">
 *   <caption>Every post-side kind, what triggers it, who receives, group-key shape</caption>
 *   <tr><th>Kind</th><th>Fires when</th><th>Receiver</th><th>Group key</th><th>Email?</th></tr>
 *
 *   <tr><td>{@link #POST_NEW}</td>
 *       <td>A user you follow publishes a post (fanned out per follower from
 *           {@code FeedTimelineService}).</td>
 *       <td>Each follower</td>
 *       <td>{@code POST_NEW:{postId}}</td>
 *       <td>No — would spam every fan-out.</td></tr>
 *
 *   <tr><td>{@link #POST_REACTED}</td>
 *       <td>Someone toggles a like ON your post via
 *           {@code CassandraReactionService.togglePostReaction}. Self-likes
 *           are suppressed.</td>
 *       <td>Post author</td>
 *       <td>{@code POST_REACTED:{postId}}</td>
 *       <td>No — aggregated in-app only. "Alice and 4 others liked your post"
 *           on next inbox open.</td></tr>
 *
 *   <tr><td>{@link #POST_COMMENTED}</td>
 *       <td>Someone writes a top-level comment on your post via
 *           {@code CassandraCommentService.createComment}.</td>
 *       <td>Post author</td>
 *       <td>{@code POST_COMMENTED:{postId}}</td>
 *       <td>Yes — first occurrence per group per hour gets an email.</td></tr>
 *
 *   <tr><td>{@link #POST_COMMENT_REPLIED}</td>
 *       <td>Someone replies to your comment via
 *           {@code CassandraCommentService.replyTo}. The depth-1 rule means
 *           a reply to a reply notifies the TOP-LEVEL comment author, not
 *           the intermediate reply author.</td>
 *       <td>Parent comment author</td>
 *       <td>{@code POST_COMMENT_REPLIED:{parentId}}</td>
 *       <td>Yes.</td></tr>
 *
 *   <tr><td>{@link #POST_COMMENT_REACTED}</td>
 *       <td>Someone likes your comment via
 *           {@code CassandraReactionService.toggleCommentReaction}.</td>
 *       <td>Comment author</td>
 *       <td>{@code POST_COMMENT_REACTED:{commentId}}</td>
 *       <td>No — aggregated in-app only.</td></tr>
 *
 *   <tr><td>{@link #POST_SHARED}</td>
 *       <td>Someone records a share of your post via
 *           {@code CassandraShareService.recordShare}.</td>
 *       <td>Post author</td>
 *       <td>{@code POST_SHARED:{postId}}</td>
 *       <td>Yes.</td></tr>
 *
 *   <tr><td>{@link #USER_MENTIONED}</td>
 *       <td>You're {@code @}-mentioned in a post's text. Extraction lives in
 *           {@code CassandraHashtagService.indexEntitiesForPost} and resolves
 *           the username against Postgres {@code UserRepository}. One
 *           notification per mentioned user per post.</td>
 *       <td>Each mentioned user</td>
 *       <td>{@code USER_MENTIONED:{postId}:{userId}}</td>
 *       <td>Yes — mentions are high-signal.</td></tr>
 * </table>
 *
 * <h3>Story / Research / Q&amp;A / System kinds</h3>
 * <p>Documented inline on each constant below.</p>
 */
public enum NotificationKind {

    // ────────────────────────────────────────────────────────────────────────
    //  USER / SOCIAL GRAPH
    // ────────────────────────────────────────────────────────────────────────

    /** A user started following you. One-shot — does NOT aggregate; every
     *  new follower deserves their own inbox row. Receiver: the followee. */
    NEW_FOLLOWER         (PrefCategory.SOCIAL,   false, true),

    /** A user who had blocked you removed the block. Surfaced so the user
     *  knows they can now interact with that account again. */
    UNBLOCKED            (PrefCategory.SOCIAL,   false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  CHAT / MESSAGING — in-app only (emailEligible=false) so an active
    //  conversation never floods a mailbox; the SSE stream is the live path,
    //  these bell rows are for offline / backgrounded recipients.
    // ────────────────────────────────────────────────────────────────────────

    /** A new direct/group message for an offline recipient. Aggregated per
     *  conversation via {@code NEW_MESSAGE:{conversationId}}. */
    NEW_MESSAGE          (PrefCategory.SOCIAL,   true,  false),

    /** A stranger's first message landed in your Message Requests inbox. */
    MESSAGE_REQUEST      (PrefCategory.SOCIAL,   false, false),

    /** Someone added you to a group conversation. */
    ADDED_TO_GROUP       (PrefCategory.SOCIAL,   false, false),

    // ────────────────────────────────────────────────────────────────────────
    //  POSTS — full details in the class Javadoc table above.
    //  All post kinds are email-eligible; per-recipient prefs + the 1h
    //  per-group email throttle keep volume sane.
    // ────────────────────────────────────────────────────────────────────────

    /** Home-feed fanout row landed; published by FeedTimelineService for
     *  every follower of the author. Email per follower per post. */
    POST_NEW             (PrefCategory.SOCIAL,   false, true),

    /** Someone liked your post. Aggregated; first like per post per hour emails. */
    POST_REACTED         (PrefCategory.SOCIAL,   true,  true),

    /** Someone commented on your post. Aggregated, email-eligible. */
    POST_COMMENTED       (PrefCategory.SOCIAL,   true,  true),

    /** Someone replied to your comment (depth-1 — replies-to-replies still
     *  notify the top-level comment author). Aggregated, email-eligible. */
    POST_COMMENT_REPLIED (PrefCategory.SOCIAL,   true,  true),

    /** Someone liked your comment. Aggregated; first like per comment per hour emails. */
    POST_COMMENT_REACTED (PrefCategory.SOCIAL,   true,  true),

    /** Someone shared your post. Aggregated, email-eligible. */
    POST_SHARED          (PrefCategory.SOCIAL,   true,  true),

    /** Legacy post-mention type, retained for backward-compat with old
     *  notification rows. New code uses {@link #USER_MENTIONED}. */
    POST_MENTIONED       (PrefCategory.MENTIONS, false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  MENTIONS
    // ────────────────────────────────────────────────────────────────────────

    /** You were {@code @}-mentioned in a post or comment. Always its own row
     *  (not aggregated) because the recipient probably wants to see each
     *  context separately. Email-eligible — gated by the MENTIONS toggle. */
    USER_MENTIONED       (PrefCategory.MENTIONS, false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  STORIES
    // ────────────────────────────────────────────────────────────────────────

    /** A user you follow published a new story. Email per follower. */
    STORY_PUBLISHED      (PrefCategory.SOCIAL,   false, true),

    /** Someone reacted to your story. Aggregated, email-eligible. */
    STORY_REACTED        (PrefCategory.SOCIAL,   true,  true),

    /** Someone replied to your story. Aggregated, email-eligible. */
    STORY_REPLIED        (PrefCategory.SOCIAL,   true,  true),

    // ────────────────────────────────────────────────────────────────────────
    //  RESEARCH
    // ────────────────────────────────────────────────────────────────────────

    /** Someone liked your published research. Aggregated, email-eligible. */
    PUBLICATION_LIKED          (PrefCategory.SOCIAL, true,  true),

    /** Someone commented on your research. Aggregated, email-eligible. */
    PUBLICATION_COMMENTED      (PrefCategory.SOCIAL, true,  true),

    /** Someone liked your comment on a research publication. Aggregated, email-eligible. */
    PUBLICATION_COMMENT_REACTED(PrefCategory.SOCIAL, true,  true),

    /** Someone cited your publication. Not aggregated — citations are
     *  individually significant. Email-eligible. */
    PUBLICATION_CITED          (PrefCategory.SOCIAL, false, true),

    /** A researcher added you as a contributor on their paper. One-shot, email. */
    RESEARCH_CONTRIBUTOR_ADDED (PrefCategory.SOCIAL, false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  QUESTIONS & ANSWERS
    // ────────────────────────────────────────────────────────────────────────

    /** A new question landed in an area you follow. Email-eligible. */
    QUESTION_NEW             (PrefCategory.SOCIAL,  false, true),

    /** Someone answered your question. Not aggregated — every answer
     *  deserves its own inbox row. Email-eligible. */
    QUESTION_ANSWERED        (PrefCategory.SOCIAL,  false, true),

    /** Someone replied to your answer. Aggregated, email-eligible. */
    ANSWER_REPLIED           (PrefCategory.SOCIAL,  true,  true),

    /** Someone reacted to your answer. Aggregated, email-eligible. */
    ANSWER_REACTED           (PrefCategory.SOCIAL,  true,  true),

    /** Your answer was accepted by the question asker. One-shot, email-eligible. */
    ANSWER_ACCEPTED          (PrefCategory.SOCIAL,  false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  SYSTEM / PLATFORM
    // ────────────────────────────────────────────────────────────────────────

    /** Your uploaded sound passed moderation. One-shot, email-eligible. */
    SOUND_APPROVED      (PrefCategory.SYSTEM, false, true),

    /** Generic system message addressed to a single user. Email-eligible. */
    SYSTEM_MESSAGE      (PrefCategory.SYSTEM, false, true),

    /** Broadcast platform announcement. Email-eligible. */
    SYSTEM_ANNOUNCEMENT (PrefCategory.SYSTEM, false, true),

    /** Moderation warning issued on your account. Always email-eligible. */
    ACCOUNT_WARNING     (PrefCategory.SYSTEM, false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  TRENDING — periodic server-pushed "what scholars/researchers
    //  are talking about" digest. Gated by its own preference category so
    //  a user can mute the daily digest without losing account warnings.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Once-a-day digest of tags trending in scholarly content (questions +
     * research). Fired by {@code TrendingNotificationJob}; one notification
     * per user per day max via the {@code TRENDING_DIGEST:{date}} group key.
     */
    TRENDING_DIGEST     (PrefCategory.TRENDING, false, true),

    // ────────────────────────────────────────────────────────────────────────
    //  CHAT — CHANNELS (in-app only, like the other chat kinds)
    // ────────────────────────────────────────────────────────────────────────

    /** Someone asked to join a channel you administer. Aggregates per channel
     *  ("@alice and 3 others requested to join") via
     *  {@code CHANNEL_JOIN_REQUEST:{channelId}}. */
    CHANNEL_JOIN_REQUEST (PrefCategory.SOCIAL, true,  false),

    /** Your join request was approved — you're in. */
    CHANNEL_JOIN_APPROVED(PrefCategory.SOCIAL, false, false),

    // ────────────────────────────────────────────────────────────────────────
    //  LIVE STREAMING
    // ────────────────────────────────────────────────────────────────────────

    /** A user you follow went live. Fanned out per follower from
     *  {@code LiveStreamFanoutService} when a stream starts. In-app + realtime
     *  only ({@code emailEligible=false}) — emailing every follower on each
     *  go-live would spam, same reasoning as {@link #POST_NEW}. One row per
     *  go-live via the {@code STREAM_STARTED:{streamId}} group key. */
    STREAM_STARTED       (PrefCategory.SOCIAL, false, false);

    /** Maps to the corresponding {@code user.email*Enabled} toggle. */
    public enum PrefCategory { SOCIAL, MENTIONS, SYSTEM, TRENDING }

    private final PrefCategory prefCategory;
    private final boolean      aggregable;
    private final boolean      emailEligible;

    NotificationKind(PrefCategory prefCategory, boolean aggregable, boolean emailEligible) {
        this.prefCategory  = prefCategory;
        this.aggregable    = aggregable;
        this.emailEligible = emailEligible;
    }

    public PrefCategory prefCategory()  { return prefCategory; }
    public boolean      aggregable()    { return aggregable; }
    public boolean      emailEligible() { return emailEligible; }
}
