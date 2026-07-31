package ak.dev.irc.app.post.cassandra.service;

/**
 * Candidate provenance labels carried on {@code FeedItemResponse.source}
 * — the frontend uses them for card chrome ("Suggested for you",
 * channel badge) and the ranker for source-specific boosts.
 */
public final class HomeFeedSources {
    /** Fanned-out content from accounts the viewer follows. */
    public static final String FOLLOWING = "FOLLOWING";
    /** The viewer's own posts (self-fanout / read-time merge). */
    public static final String SELF      = "SELF";
    /** Digest post from a subscribed broadcast channel. */
    public static final String CHANNEL   = "CHANNEL";
    /** Exploration slice — trending content from non-followed authors. */
    public static final String EXPLORE   = "EXPLORE";

    private HomeFeedSources() {}
}
