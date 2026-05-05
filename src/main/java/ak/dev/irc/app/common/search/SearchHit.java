package ak.dev.irc.app.common.search;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified search-result row — same shape regardless of corpus so the client
 * can render a generic results list without per-type branching.
 */
@Data
@Builder
public class SearchHit {

    private SearchType type;
    private UUID id;

    /** Headline of the row (post text snippet, research title, question title, …). */
    private String title;

    /** Optional secondary text for richer rendering (post author, research abstract, …). */
    private String snippet;

    /** Author / owner reference. Null for objects without an owner (system content). */
    private UUID authorId;
    private String authorUsername;
    private String authorFullName;
    private String authorAvatarUrl;

    /** Optional thumbnail / media preview URL. */
    private String thumbnailUrl;

    /** Pre-built deep-link the client can navigate to (e.g. {@code /posts/abc}). */
    private String deepLink;

    /** Postgres {@code ts_rank_cd} score — higher is more relevant. */
    private double score;

    /** Created timestamp (used for tiebreaker sorting client-side if desired). */
    private LocalDateTime createdAt;
}
