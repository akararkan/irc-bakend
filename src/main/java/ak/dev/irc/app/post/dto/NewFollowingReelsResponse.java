package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/posts/reels/following/new-count} — the Following-tab badge.
 *
 * @param count   unwatched reels from followed accounts, posted after {@code since}
 * @param since   the freshness floor the count was taken against ({@code now − 7d});
 *                {@code null} for an anonymous caller
 * @param postIds the counted reels, newest first — so a client can strike one the
 *                moment it is watched instead of asking again
 */
public record NewFollowingReelsResponse(int count, Instant since, List<UUID> postIds) {

    public static NewFollowingReelsResponse empty() {
        return new NewFollowingReelsResponse(0, null, List.of());
    }
}
