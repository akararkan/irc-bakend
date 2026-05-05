package ak.dev.irc.app.research.realtime;

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
    private UUID parentCommentId;

    /** Reaction enum name for REACTION_* events. */
    private String reactionType;
    private String previousReactionType;

    /** Snippet for COMMENT_CREATED / EDITED / REPLY_CREATED. */
    private String body;

    // ── Fresh denormalised counters after the event was applied ──────
    private Long reactionCount;
    private Long commentCount;
    private Long shareCount;
    private Long saveCount;
    private Long viewCount;
    private Long downloadCount;
    private Long citationCount;
    private Long commentReplyCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
