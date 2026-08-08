package ak.dev.irc.app.moderation.entity;

import ak.dev.irc.app.moderation.enums.ModerationVerdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One text field belonging to a {@link ModerationCase} —
 * {@code moderation_fields} in MODERATION_ROADMAP.md §9.
 *
 * <p>Fields are scored independently so the queue can say <em>which</em> field
 * tripped ("the third tag", not "the post"), while the entity-level verdict is
 * the worst of them (§5.4).</p>
 */
@Entity
@Table(name = "moderation_case_fields",
        indexes = @Index(name = "idx_mod_field_case", columnList = "case_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationCaseField {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mod_field_case"))
    private ModerationCase moderationCase;

    /** {@code title}, {@code body}, {@code tag}, {@code alt_text}, {@code caption}, … */
    @Column(name = "field_name", nullable = false, length = 60)
    private String fieldName;

    /**
     * The submitted text, verbatim. Retained so a moderator can review what was
     * actually said even after the content row is gone; treated as user content
     * for retention/erasure purposes (§15).
     */
    @Column(name = "text", columnDefinition = "text", nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 20)
    private ModerationVerdict verdict;

    /** Raw per-label probabilities from the inference service, as JSON. */
    @Column(name = "scores", columnDefinition = "text")
    private String scoresJson;

    @Column(name = "max_label", length = 30)
    private String maxLabel;

    @Column(name = "max_score")
    @Builder.Default
    private Double maxScore = 0.0;

    @Column(name = "blocklist_hit", length = 100)
    private String blocklistHit;
}
