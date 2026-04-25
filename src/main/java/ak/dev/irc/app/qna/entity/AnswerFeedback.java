package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.qna.enums.FeedbackType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "answer_feedbacks",
        indexes = {
                @Index(name = "idx_feedback_answer", columnList = "answer_id"),
                @Index(name = "idx_feedback_author", columnList = "author_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_feedback_answer_author",
                        columnNames = {"answer_id", "author_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerFeedback extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedback_answer"))
    private QuestionAnswer answer;

    /** The scholar giving the feedback (typically the question author). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedback_author"))
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 30)
    private FeedbackType feedbackType;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;
}