package ak.dev.irc.app.qna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent view ledger for questions — one row per (question, user) pair,
 * ever. Mirrors {@code PostView} / {@code ResearchView}. Replaces the previous
 * Redis 1h-window dedup so each authenticated user counts as a single view
 * forever.
 */
@Entity
@Table(name = "question_views", indexes = {
        @Index(name = "idx_question_view_user", columnList = "user_id"),
        @Index(name = "idx_question_view_question", columnList = "question_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionView {

    @EmbeddedId
    private QuestionViewId id;

    @CreationTimestamp
    @Column(name = "first_viewed_at", updatable = false, nullable = false)
    private LocalDateTime firstViewedAt;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class QuestionViewId implements Serializable {
        @Column(name = "question_id", nullable = false)
        private UUID questionId;
        @Column(name = "user_id", nullable = false)
        private UUID userId;
    }
}
