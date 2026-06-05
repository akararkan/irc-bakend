package ak.dev.irc.app.post.enums;

/**
 * Top-level discriminator for what kind of entity a {@code feed_by_user}
 * row represents. Lets the home timeline mix posts, research and Q&A
 * in a single chronological stream while keeping each entity's detail
 * endpoint independent.
 *
 * <p>Storage: written into {@code feed_by_user.entity_type} as a plain
 * string. Rows that pre-date the column (i.e. written before this
 * feature shipped) read back as {@code null} — code treats {@code null}
 * as {@link #POST} for back-compat. The Cassandra migration is
 * additive: {@code ALTER TABLE feed_by_user ADD entity_type text;}</p>
 *
 * <p>Read path: returned to the client as part of {@code FeedItemResponse}
 * so the frontend can route each card to the right detail endpoint
 * ({@code /posts/{id}}, {@code /researches/{id}}, {@code /questions/{id}}).</p>
 */
public enum FeedEntityType {
    POST,
    RESEARCH,
    QUESTION;

    /** Null-safe parse — unknown / missing values default to {@link #POST}. */
    public static FeedEntityType parse(String s) {
        if (s == null || s.isBlank()) return POST;
        try { return FeedEntityType.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return POST; }
    }
}
