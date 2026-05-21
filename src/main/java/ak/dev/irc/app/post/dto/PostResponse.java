package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a single post. Flattens the canonical Cassandra row
 * and embeds the author profile so the frontend renders without a
 * second round-trip.
 *
 * <p>{@code savedAt} and {@code savedCollectionName} are populated only
 * when this response is built from a saved-posts list endpoint
 * (e.g. {@code GET /api/v1/posts/users/{userId}/saves}). On every other
 * endpoint they are {@code null}.</p>
 */
public record PostResponse(
        UUID    id,
        UUID    authorId,
        AuthorSummary author,
        String  postType,
        String  status,
        String  visibility,
        String  textContent,
        String  audioTrackUrl,
        String  audioTrackName,
        String  locationName,
        Double  locationLat,
        Double  locationLng,
        UUID    sharedPostId,
        String  shareLink,
        List<String> mediaUrls,
        List<String> mediaTypes,
        // ── Live denormalised counters from post_counters (Cassandra) ────
        long    reactionCount,
        long    commentCount,
        long    viewCount,
        long    saveCount,
        long    shareCount,
        // ── Viewer-relative flags (so the heart / bookmark icons render correctly) ──
        boolean likedByMe,
        boolean savedByMe,
        Instant createdAt,
        Instant updatedAt,
        // ── Only populated by saved-list endpoints; null elsewhere ───────
        /** When the viewer bookmarked this post. */
        Instant savedAt,
        /** Collection / folder name on the save row ({@code "Default"} when none was set). */
        String  savedCollectionName
) {}
