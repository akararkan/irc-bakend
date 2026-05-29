package ak.dev.irc.app.qna.realtime;

import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single payload broadcast on a question's realtime channel
 * ({@code irc:questions:{questionId}}).
 *
 * <p>One shape covers every event so clients can dispatch on
 * {@link #eventType} and read whichever fields are populated. Null fields
 * are omitted from the wire format to keep frames small for high-frequency
 * streams (reactions, view-count fan-outs).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QnaRealtimeEvent {

    private QnaRealtimeEventType eventType;

    private UUID questionId;

    /** Actor that triggered the event. May be null for system-generated events. */
    private UUID actorId;
    private String actorUsername;
    private String actorAvatarUrl;

    /** Answer id for ANSWER_* / REANSWER_*. */
    private UUID answerId;
    /**
     * Root answer id when the affected answer is a reply (reanswer); null when
     * it is a top-level answer. Populated on <strong>every</strong> answer-scoped
     * event so clients can route the update to the correct thread —
     * {@code threadId = parentAnswerId ?? answerId}.
     */
    private UUID parentAnswerId;

    /**
     * The full, freshly-recomputed answer for every answer-scoped event
     * (created / edited / deleted / reaction / accept / best-vote), so clients
     * patch the row in place with <strong>no refetch</strong>. Viewer-specific
     * fields ({@code myReaction}, {@code votedByMe}) are neutral here — resolve
     * them per-viewer. On {@code ANSWER_DELETED} this carries the tombstone
     * ({@code deleted:true}, body/attachments/sources nulled).
     */
    private QuestionAnswerResponse answer;

    /** Reaction enum name for ANSWER_REACTION_*. */
    private String reactionType;
    /** Previous reaction (for ANSWER_REACTION_CHANGED). */
    private String previousReactionType;

    /** Body snapshot for ANSWER_CREATED / ANSWER_EDITED / REANSWER_CREATED. */
    private String body;

    // ── Fresh denormalised counters ────────────────────────────────────
    private Long questionAnswerCount;
    /** Fresh view count on the question — populated on {@code VIEW_COUNT_UPDATED}. */
    private Long questionViewCount;
    /** Fresh save (bookmark) count on the question — populated on {@code SAVE_COUNT_UPDATED}. */
    private Long questionSaveCount;
    /** Fresh share count on the question — populated on {@code SHARE_COUNT_UPDATED}. */
    private Long shareCount;
    private Long answerReactionCount;
    private Long answerReplyCount;
    /** Number of distinct scholars that have voted this answer as best. */
    private Long bestAnswerVoteCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
