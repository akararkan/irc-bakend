package ak.dev.irc.app.qna.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key for {@link BestAnswerVote}: one (answer, scholar) pair is
 * the natural primary key — a scholar can vote at most once per answer.
 */
@Data
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class BestAnswerVoteId implements Serializable {

    @Column(name = "answer_id", nullable = false)
    private UUID answerId;

    @Column(name = "voter_id", nullable = false)
    private UUID voterId;
}
