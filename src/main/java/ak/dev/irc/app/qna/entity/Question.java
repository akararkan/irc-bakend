package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Free-text keywords the author adds for discoverability (comma-separated,
     * not normalized). Indexed in Elasticsearch and boosted in global search.
     */
    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    /**
     * Normalized tags (lowercase, trimmed). Stored here for display + ES, and
     * fanned out to the Cassandra tag subsystem for trending and tag feeds.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "question_tags",
            joinColumns = @JoinColumn(name = "question_id"),
            indexes = @Index(name = "idx_qtag_name", columnList = "tag_name"))
    @Column(name = "tag_name", length = 100)
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QuestionStatus status = QuestionStatus.OPEN;

    @Column(name = "answer_count", nullable = false)
    @Builder.Default
    private Long answerCount = 0L;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(name = "save_count", nullable = false)
    @Builder.Default
    private Long saveCount = 0L;

    @Column(name = "share_count", nullable = false)
    @Builder.Default
    private Long shareCount = 0L;

    /**
     * Number of answers the author has marked accepted. Denormalized so feed
     * cards can badge "resolved" ({@code hasAcceptedAnswer = acceptedAnswerCount > 0})
     * without loading answers. Maintained on accept / unaccept / delete.
     */
    @Column(name = "accepted_answer_count", nullable = false)
    @Builder.Default
    private Long acceptedAnswerCount = 0L;

    /** When true, no new answers can be submitted. */
    @Column(name = "answers_locked", nullable = false)
    @Builder.Default
    private boolean answersLocked = false;

    /** Maximum number of answers allowed. Null means unlimited. */
    @Column(name = "max_answers")
    private Integer maxAnswers;

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