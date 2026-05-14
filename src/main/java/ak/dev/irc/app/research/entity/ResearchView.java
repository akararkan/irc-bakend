package ak.dev.irc.app.research.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent view ledger for research papers — one row per (research, user)
 * pair, ever. Mirrors {@code PostView}. Replaces the previous Redis
 * 1h-window dedup so each authenticated user counts as a single view forever.
 */
@Entity
@Table(name = "research_views", indexes = {
        @Index(name = "idx_research_view_user", columnList = "user_id"),
        @Index(name = "idx_research_view_research", columnList = "research_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchView {

    @EmbeddedId
    private ResearchViewId id;

    @CreationTimestamp
    @Column(name = "first_viewed_at", updatable = false, nullable = false)
    private LocalDateTime firstViewedAt;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class ResearchViewId implements Serializable {
        @Column(name = "research_id", nullable = false)
        private UUID researchId;
        @Column(name = "user_id", nullable = false)
        private UUID userId;
    }
}
