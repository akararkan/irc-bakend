package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Best-answer vote — any scholar may mark any top-level answer as a best
 * answer. Multiple scholars may independently vote for the same or different
 * answers; the denormalised {@code bestAnswerVoteCount} on
 * {@link QuestionAnswer} mirrors the row count for fast listings.
 */
@Entity
@Table(
        name = "best_answer_votes",
        indexes = {
                @Index(name = "idx_bav_voter",  columnList = "voter_id"),
                @Index(name = "idx_bav_answer", columnList = "answer_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BestAnswerVote extends BaseAuditEntity {

    @EmbeddedId
    private BestAnswerVoteId id;

    @MapsId("answerId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_bav_answer"))
    private QuestionAnswer answer;

    @MapsId("voterId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voter_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_bav_voter"))
    private User voter;

    public static BestAnswerVote of(QuestionAnswer answer, User voter) {
        return BestAnswerVote.builder()
                .id(new BestAnswerVoteId(answer.getId(), voter.getId()))
                .answer(answer)
                .voter(voter)
                .build();
    }
}
