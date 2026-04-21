package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "questions",
        indexes = {
                @Index(name = "idx_question_author", columnList = "author_id"),
                @Index(name = "idx_question_status", columnList = "status"),
                @Index(name = "idx_question_deleted", columnList = "deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_question_author"))
    private User author;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QuestionStatus status = QuestionStatus.OPEN;

    @Column(name = "answer_count", nullable = false)
    @Builder.Default
    private Long answerCount = 0L;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<QuestionAnswer> answers = new ArrayList<>();

    public boolean isDeleted() {
        return deletedAt != null;
    }
}