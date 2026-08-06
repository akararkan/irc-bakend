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
 * One row per admin moderation verdict — who did what to which target and why
 * (content-moderation.md §3 "decision log"). The takedowns/day source of
 * truth; {@code target_ref} is a string so non-UUID targets (chat Snowflakes)
 * fit too. Rows are never deleted.
 */
@Entity
@Table(name = "moderation_decisions",
        indexes = {
                @Index(name = "idx_mod_decision_target", columnList = "target_type, target_ref"),
                @Index(name = "idx_mod_decision_time", columnList = "created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_ref", nullable = false, length = 64)
    private String targetRef;

    /** The blueprint audit action, e.g. {@code ADMIN_POST_REMOVE}. */
    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "actor_id")
    private UUID actorId;

    /** Free-form context snapshot, e.g. "useCount=3200" for a sound takedown. */
    @Column(name = "metadata", length = 500)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
