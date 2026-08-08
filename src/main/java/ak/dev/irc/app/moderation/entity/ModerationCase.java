package ak.dev.irc.app.moderation.entity;

import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One submitted content unit moving through the moderation state machine —
 * {@code moderation_entities} in MODERATION_ROADMAP.md §9, named "case" here so
 * it does not read as a JPA entity for the content itself.
 *
 * <p>The case owns its own copy of the text ({@link ModerationCaseField}) rather
 * than pointing at the content row. That is deliberate: the review queue must
 * still render what was submitted after a hard delete, a Cassandra TTL expiry,
 * or an edit — none of which this table controls.</p>
 *
 * <p>{@code entityRef} is a String, not a UUID: posts and research use UUIDs but
 * chat messages are 64-bit snowflakes, and the same table has to hold both. It
 * mirrors {@code ModerationDecision.targetRef}, which made the same call.</p>
 */
@Entity
@Table(name = "moderation_cases",
        indexes = {
                @Index(name = "idx_mod_case_status_deadline", columnList = "status, hold_deadline"),
                @Index(name = "idx_mod_case_target", columnList = "entity_type, entity_ref"),
                @Index(name = "idx_mod_case_author", columnList = "author_id, submitted_at"),
                @Index(name = "idx_mod_case_queue", columnList = "status, max_score")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private ModeratedEntityType entityType;

    /** Natural id of the content unit — UUID string, snowflake, or a synthetic ref for ephemeral text. */
    @Column(name = "entity_ref", nullable = false, length = 64)
    private String entityRef;

    /** Threaded content: answer → question, reply → comment, comment → post (§5.4). */
    @Column(name = "parent_ref", length = 64)
    private String parentRef;

    @Column(name = "author_id")
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ModerationStatus status = ModerationStatus.PENDING;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    /** Past this instant with no verdict, the SLA sweeper applies the fallback policy (§5.6). */
    @Column(name = "hold_deadline", nullable = false)
    private LocalDateTime holdDeadline;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** {@code "system"} for an automated verdict, otherwise the admin's user id. */
    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    /** Which label drove the verdict, so the queue can sort by what actually tripped. */
    @Column(name = "max_label", length = 30)
    private String maxLabel;

    @Column(name = "max_score")
    @Builder.Default
    private Double maxScore = 0.0;

    /** Normalized blocklist term that matched, if any (§8.2). */
    @Column(name = "blocklist_hit", length = 100)
    private String blocklistHit;

    /** Machine-readable why: {@code BLOCKLIST}, {@code MODEL}, {@code SLA_BREACH}, {@code ADMIN}. */
    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    /** Free-text reason on an admin override. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** True when the hold window expired before an automated verdict (§5.6). */
    @Column(name = "sla_breached", nullable = false)
    @Builder.Default
    private boolean slaBreached = false;

    /** Scoring attempts so far — lets the worker stop retrying a permanently failing case. */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** An edit re-enters moderation; the previous case for the same ref is superseded (§5.5). */
    @Column(name = "revision", nullable = false)
    @Builder.Default
    private int revision = 1;

    /**
     * Set when the applier has actually published/hidden the underlying content.
     * A verdict recorded here but never applied would be an invisible failure, so
     * the sweeper re-drives cases that are decided but unapplied.
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "apply_error", length = 300)
    private String applyError;

    /**
     * When the author was last told about this case's outcome.
     *
     * <p>Needed because {@code IN_REVIEW} is not a terminal state, so it never
     * stamps {@link #appliedAt} — without this, every re-drive (an admin hitting
     * rescore, a sweeper pass) would send the author another "your content is
     * being reviewed" notification for the same case.</p>
     */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @OneToMany(mappedBy = "moderationCase", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<ModerationCaseField> fields = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
        if (holdDeadline == null) holdDeadline = submittedAt.plusSeconds(30);
    }

    public void addField(ModerationCaseField field) {
        field.setModerationCase(this);
        fields.add(field);
    }

    /** True when a verdict exists but the content has not been flipped yet. */
    public boolean awaitingApply() {
        return status.terminal() && appliedAt == null;
    }
}
