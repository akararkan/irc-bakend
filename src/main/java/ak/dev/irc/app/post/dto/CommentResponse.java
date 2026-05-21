package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/** Comment with author profile inlined — no client-side hydration needed.
 *  The primary identifier is named {@code id} (matching {@code PostResponse}
 *  and the standard {@code entity.id} JS convention). */
public record CommentResponse(
        UUID    id,
        UUID    postId,
        UUID    authorId,
        AuthorSummary author,
        String  textContent,
        String  mediaUrl,
        String  mediaType,
        // ── Live counters from comment_counters ────────────────────────
        long    reactionCount,
        long    replyCount,
        boolean likedByMe,
        Boolean deleted,
        Boolean edited,
        Instant createdAt
) {}
