package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/** Reply with author profile inlined.
 *  Primary identifier is {@code id}; {@code parentId} points to the comment
 *  this reply hangs under. */
public record ReplyResponse(
        UUID    id,
        UUID    parentId,
        UUID    postId,
        UUID    authorId,
        AuthorSummary author,
        String  textContent,
        String  mediaUrl,
        long    reactionCount,
        boolean likedByMe,
        Boolean deleted,
        Boolean edited,
        Instant createdAt
) {}
