package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one entry in a profile / home feed / reels list.
 * Carries the author summary inline so the UI never shows
 * "unknown user". Primary identifier is {@code id} to match
 * {@code PostResponse} and the {@code entity.id} JS convention.
 */
public record FeedItemResponse(
        UUID    id,
        UUID    authorId,
        AuthorSummary author,
        String  postType,
        String  textPreview,
        String  mediaUrl,
        String  videoUrl,   // REEL only — first VIDEO-type URL from posts_by_id; null for all other types
        // ── Live counters from post_counters ───────────────────────────
        long    reactionCount,
        long    commentCount,
        long    viewCount,
        long    saveCount,
        long    shareCount,
        boolean likedByMe,
        boolean savedByMe,
        Instant createdAt
) {}
