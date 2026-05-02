package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "answer_reactions",
        indexes = {
                @Index(name = "idx_areaction_answer", columnList = "answer_id"),
                @Index(name = "idx_areaction_user", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerReaction {

    @EmbeddedId
    private AnswerReactionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("answerId")
    @JoinColumn(name = "answer_id",
            foreignKey = @ForeignKey(name = "fk_areaction_answer"))
    private QuestionAnswer answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_areaction_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 20)
    private AnswerReactionType reactionType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
