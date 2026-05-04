package ak.dev.irc.app.qna.realtime;

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

    /** Answer id for ANSWER_* / REANSWER_* / FEEDBACK_*. */
    private UUID answerId;
    /** Parent answer id for REANSWER_CREATED. */
    private UUID parentAnswerId;

    /** Feedback id for FEEDBACK_*. */
    private UUID feedbackId;

    /** Reaction enum name for ANSWER_REACTION_*. */
    private String reactionType;
    /** Previous reaction (for ANSWER_REACTION_CHANGED). */
    private String previousReactionType;

    /** Body snapshot for ANSWER_CREATED / ANSWER_EDITED / REANSWER_CREATED. */
    private String body;

    /** Optional feedback type label for FEEDBACK_* events. */
    private String feedbackType;

    // ── Fresh denormalised counters ────────────────────────────────────
    private Long questionAnswerCount;
    private Long answerReactionCount;
    private Long answerReplyCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
