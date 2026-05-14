package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One row per (question, user) pair — the user's bookmark of a question.
 * Mirrors {@code PostSave} and {@code ResearchSave} so the front-end can
 * use the same save / unsave / collections pattern across all three modules.
 *
 * <p>Composite PK on {@code (question_id, user_id)} prevents double-saves;
 * an idempotent {@code existsById} check in the service short-circuits the
 * counter bump if the row already exists.</p>
 */
@Entity
@Table(
    name = "question_saves",
    indexes = {
        @Index(name = "idx_qsave_question", columnList = "question_id"),
        @Index(name = "idx_qsave_user", columnList = "user_id"),
        @Index(name = "idx_qsave_user_collection", columnList = "user_id, collection_name")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionSave {

    @EmbeddedId
    private QuestionSaveId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id",
                foreignKey = @ForeignKey(name = "fk_qsave_question"))
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_qsave_user"))
    private User user;

    @Column(name = "collection_name", length = 100)
    private String collectionName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
