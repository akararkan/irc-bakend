package ak.dev.irc.app.admin.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Integrity flag on a research item (research-qna.md §4 — PLAGIARISM/QUALITY
 * review queue). Flags never alter the research itself; they feed the admin
 * review view until resolved.
 */
@Entity
@Table(name = "research_flags",
        indexes = {
                @Index(name = "idx_research_flag_target", columnList = "research_id"),
                @Index(name = "idx_research_flag_open", columnList = "resolved_at, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchFlag {

    public enum FlagType {PLAGIARISM, QUALITY}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "research_id", nullable = false)
    private UUID researchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private FlagType type;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "flagged_by")
    private UUID flaggedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
