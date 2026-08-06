package ak.dev.irc.app.admin.moderation;

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
 * Pre-deletion snapshot store (content-moderation.md §2.4/§2.5): comments are
 * hard-deleted with their reply subtree and stories TTL out, so evidence must
 * be frozen BEFORE the destructive action. Media bytes are never copied —
 * only text + media references. Append-only; no admin endpoint may mutate it.
 */
@Entity
@Table(name = "moderation_evidence",
        indexes = @Index(name = "idx_mod_evidence_target", columnList = "target_type, target_ref"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_ref", nullable = false, length = 64)
    private String targetRef;

    @Column(name = "author_id")
    private UUID authorId;

    /** JSON snapshot of the content at capture time (text fields, counters). */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    /** Comma-separated media/R2 references (never the bytes). */
    @Column(name = "media_refs", columnDefinition = "text")
    private String mediaRefs;

    @Column(name = "captured_by")
    private UUID capturedBy;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    void onCreate() {
        if (capturedAt == null) capturedAt = LocalDateTime.now();
    }
}
