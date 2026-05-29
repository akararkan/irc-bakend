package ak.dev.irc.app.common.tag;

/**
 * The kinds of content that can be tagged and counted toward trending.
 *
 * <p>The {@link #SCOPE_ALL} constant is the cross-type bucket: every tag write
 * increments both its own type's counter and the {@code ALL} counter, so the
 * trending ranker can serve a global leaderboard as well as per-type ones from
 * the same {@code tag_counters} table.</p>
 *
 * <p>Posts and reels share an entity (post_by_id) but appear as distinct content
 * types here so a tag feed / trending breakdown can split short-form video from
 * the rest. The post-side write path picks {@link #REEL} when
 * {@code postType="REEL"}, else {@link #POST}.</p>
 */
public enum ContentType {
    QUESTION,
    RESEARCH,
    POST,
    REEL;

    /** Cross-type trending bucket name (not a real content type). */
    public static final String SCOPE_ALL = "ALL";
}
