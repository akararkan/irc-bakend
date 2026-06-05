package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one entry in a profile / home feed / reels list.
 * Carries the author summary inline so the UI never shows
 * "unknown user". Primary identifier is {@code id} to match
 * {@code PostResponse} and the {@code entity.id} JS convention.
 *
 * <p><b>{@code entityType}</b> is the top-level discriminator —
 * {@code POST | RESEARCH | QUESTION}. The frontend reads it FIRST
 * and dispatches to the right detail endpoint
 * ({@code /api/v1/posts/{id}}, {@code /api/v1/researches/{id}},
 * {@code /api/v1/questions/{id}}). {@code postType} is a sub-flavour
 * inside POST (POST / REEL / SHARE) and is ignored for non-POST
 * entries — for RESEARCH/QUESTION it carries a label like
 * "PUBLICATION" or "QUESTION" purely for UI badge convenience.</p>
 *
 * <p>Counter fields (reactionCount … shareCount) are zero for non-POST
 * entries — research/Q&A counters are NOT bulk-hydrated at feed read
 * time (would create cross-domain coupling); the frontend pulls them
 * from the entity's own detail endpoint on click-through, or shows the
 * card without numbers.</p>
 */
public record FeedItemResponse(
        UUID    id,
        UUID    authorId,
        AuthorSummary author,
        String  entityType,
        String  postType,
        String  textPreview,
        String  mediaUrl,
        String  videoUrl,   // REEL only — first VIDEO-type URL from posts_by_id; null for all other types
        // ── Live counters from post_counters (POST entries only; 0 for research/qna) ──
        long    reactionCount,
        long    commentCount,
        long    viewCount,
        long    saveCount,
        long    shareCount,
        boolean likedByMe,
        boolean savedByMe,
        Instant createdAt
) {}
