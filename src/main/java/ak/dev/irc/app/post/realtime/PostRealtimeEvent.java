package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single payload broadcast on a post's realtime channel.
 *
 * One shape covers every event so clients can dispatch on {@link #eventType}
 * and read whichever fields are populated. Null fields are omitted from the
 * wire format to keep frames small for high-frequency streams (reactions,
 * view counts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PostRealtimeEvent {

    private PostRealtimeEventType eventType;

    private UUID postId;

    /** User who triggered the event. May be null for anonymous view bumps. */
    private UUID actorId;
    private String actorUsername;
    private String actorAvatarUrl;

    /** Comment id for COMMENT_* / REPLY_* / COMMENT_REACTION_* events. */
    private UUID commentId;

    /** Parent comment id for REPLY_CREATED. */
    private UUID parentCommentId;

    /** Reaction emoji enum name (LIKE, LOVE, ...) for REACTION_* / COMMENT_REACTION_*. */
    private String reactionType;
    /** Previous reaction (for REACTION_CHANGED / COMMENT_REACTION_CHANGED). */
    private String previousReactionType;

    /** Comment text snapshot for COMMENT_CREATED / COMMENT_EDITED / REPLY_CREATED. */
    private String textContent;
    private String mediaUrl;
    private String mediaType;
    private String mediaThumbnailUrl;

    // ── Fresh denormalised counters after the event was applied ──────────
    private Long postReactionCount;
    private Long postCommentCount;
    private Long postShareCount;
    private Long postViewCount;
    private Long commentReactionCount;
    private Long commentReplyCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
