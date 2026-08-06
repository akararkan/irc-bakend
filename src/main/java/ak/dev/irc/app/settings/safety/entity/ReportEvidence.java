package ak.dev.irc.app.settings.safety.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Snapshot-at-submit evidence (safety-reports.md §3.3): reported content is
 * frozen the moment the FIRST report of a group lands, because the live
 * stores are volatile — comments hard-delete with their reply subtree,
 * stories carry row TTLs, profiles are freely editable. Media bytes are never
 * copied, only references. Append-only; no admin endpoint may mutate it.
 */
@Entity
@Table(name = "report_evidence",
        indexes = @Index(name = "idx_report_evidence_group", columnList = "group_key", unique = true))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "group_key", nullable = false, length = 80)
    private String groupKey;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_ref", nullable = false, length = 64)
    private String targetRef;

    @Column(name = "author_id")
    private UUID authorId;

    /** Truncated text of the target at capture time. */
    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    /** Comma-separated media/R2 references (never the bytes). */
    @Column(name = "media_refs", columnDefinition = "text")
    private String mediaRefs;

    /** Live-store state marker at capture ("PUBLISHED", "not captured", …). */
    @Column(name = "entity_state", length = 60)
    private String entityState;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    void onCreate() {
        if (capturedAt == null) capturedAt = LocalDateTime.now();
    }
}
