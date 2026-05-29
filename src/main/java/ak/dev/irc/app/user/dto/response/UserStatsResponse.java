package ak.dev.irc.app.user.dto.response;

/**
 * Live profile-stat counts for a user, aggregated across stores
 * (posts → Cassandra, research/questions → Postgres, follows → Postgres).
 *
 * <p>Semantics: {@code researchCount} is PUBLISHED-only; {@code questionCount}
 * excludes soft-deleted; {@code postCount} is the author's live non-reel posts
 * and {@code reelCount} their reels (split so each matches its own profile tab;
 * a delete removes the row). Each count matches its corresponding list endpoint's
 * filter.</p>
 */
public record UserStatsResponse(
        long postCount,
        long reelCount,
        long researchCount,
        long questionCount,
        long followerCount,
        long followingCount
) {}
