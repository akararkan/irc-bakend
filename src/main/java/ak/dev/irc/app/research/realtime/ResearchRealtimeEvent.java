package ak.dev.irc.app.research.realtime;

import ak.dev.irc.app.research.dto.response.CommentResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire payload broadcast on a research's realtime channel
 * ({@code irc:research:{researchId}}). One shape covers every event so
 * clients can dispatch on {@link #eventType} and read whichever fields are
 * populated. Null fields are stripped to keep the payload small for the
 * high-frequency counter updates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResearchRealtimeEvent {

    private ResearchRealtimeEventType eventType;
    private UUID researchId;

    private UUID actorId;
    private String actorUsername;
    private String actorAvatarUrl;

    /** Comment id for COMMENT_* / REPLY_*. */
    private UUID commentId;
    /** Root comment id when this is a reply; null for top-level comments. */
    private UUID parentCommentId;

    /**
     * The full, freshly-mapped comment for COMMENT_CREATED / REPLY_CREATED /
     * COMMENT_EDITED / COMMENT_REACTION_* — patch the row in place, no refetch (A1).
     * Viewer-specific {@code myReaction} is neutral (null); resolve per-viewer.
     */
    private CommentResponse comment;

    /** Research status for RESEARCH_PUBLISHED / RESEARCH_UPDATED / RESEARCH_DELETED lifecycle events (A4). */
    private String status;

    /** Reaction enum name for REACTION_* events. */
    private String reactionType;
    private String previousReactionType;

    /** Snippet for COMMENT_CREATED / EDITED / REPLY_CREATED. */
    private String body;

    /** Inline media on the comment (image / video) — populated on COMMENT_* / REPLY_* events. */
    private String mediaUrl;
    private String mediaType;
    private String mediaThumbnailUrl;

    // ── Fresh denormalised counters after the event was applied ──────
    private Long reactionCount;
    private Long commentCount;
    private Long shareCount;
    private Long saveCount;
    private Long viewCount;
    private Long downloadCount;
    private Long citationCount;
    private Long commentReplyCount;
    /**
     * Fresh total-reactions count on the comment for COMMENT_REACTION_* events
     * (also published on the legacy {@code commentLikeCount} alias below for
     * any frontend that hasn't migrated to the new key yet).
     */
    private Long commentReactionCount;
    /** @deprecated use {@link #commentReactionCount}. Kept on the wire for back-compat. */
    @Deprecated
    private Long commentLikeCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
