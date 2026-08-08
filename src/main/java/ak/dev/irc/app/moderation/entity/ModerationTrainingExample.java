package ak.dev.irc.app.moderation.entity;

import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One labeled {@code (text, 6 binary labels)} row — {@code training_examples} in
 * MODERATION_ROADMAP.md §9. This table is the dataset the training container
 * fine-tunes on, and it is the whole point of §13: every admin correction lands
 * here instead of evaporating into a support ticket.
 *
 * <p>Append-mostly. Rows are editable (to fix a bad label before it is trained
 * on) and deletable from the dataset browser (§12.3), but nothing in the
 * automated path ever rewrites them.</p>
 */
@Entity
@Table(name = "moderation_training_examples",
        indexes = {
                @Index(name = "idx_mod_train_source", columnList = "source, added_at"),
                @Index(name = "idx_mod_train_added", columnList = "added_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationTrainingExample {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "text", columnDefinition = "text", nullable = false)
    private String text;

    /**
     * Normalized form of {@link #text}, unique-indexed to keep the dataset from
     * filling up with the same sentence promoted twice from two review rows.
     */
    @Column(name = "text_hash", nullable = false, length = 64, unique = true)
    private String textHash;

    @Column(name = "toxic", nullable = false)
    @Builder.Default
    private short toxic = 0;

    @Column(name = "severe_toxic", nullable = false)
    @Builder.Default
    private short severeToxic = 0;

    @Column(name = "obscene", nullable = false)
    @Builder.Default
    private short obscene = 0;

    @Column(name = "threat", nullable = false)
    @Builder.Default
    private short threat = 0;

    @Column(name = "insult", nullable = false)
    @Builder.Default
    private short insult = 0;

    @Column(name = "identity_hate", nullable = false)
    @Builder.Default
    private short identityHate = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private TrainingExampleSource source;

    /** The moderation case this was promoted from, when it came out of the queue. */
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    /** Set once this row has been included in a completed training run. */
    @Column(name = "trained_in_version", length = 40)
    private String trainedInVersion;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = LocalDateTime.now();
    }

    /** Wire shape the training container expects: {@code {"toxic": 1, ...}}. */
    public Map<String, Integer> labelMap() {
        Map<String, Integer> labels = new LinkedHashMap<>();
        labels.put(ModerationLabel.TOXIC.wire(), (int) toxic);
        labels.put(ModerationLabel.SEVERE_TOXIC.wire(), (int) severeToxic);
        labels.put(ModerationLabel.OBSCENE.wire(), (int) obscene);
        labels.put(ModerationLabel.THREAT.wire(), (int) threat);
        labels.put(ModerationLabel.INSULT.wire(), (int) insult);
        labels.put(ModerationLabel.IDENTITY_HATE.wire(), (int) identityHate);
        return labels;
    }

    public void applyLabels(Map<String, ?> labels) {
        if (labels == null) return;
        toxic = flag(labels.get(ModerationLabel.TOXIC.wire()));
        severeToxic = flag(labels.get(ModerationLabel.SEVERE_TOXIC.wire()));
        obscene = flag(labels.get(ModerationLabel.OBSCENE.wire()));
        threat = flag(labels.get(ModerationLabel.THREAT.wire()));
        insult = flag(labels.get(ModerationLabel.INSULT.wire()));
        identityHate = flag(labels.get(ModerationLabel.IDENTITY_HATE.wire()));
    }

    /** True when no label is set — a clean/negative example, which the dataset needs too. */
    public boolean clean() {
        return (toxic | severeToxic | obscene | threat | insult | identityHate) == 0;
    }

    private static short flag(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean b) return (short) (b ? 1 : 0);
        if (value instanceof Number n) return (short) (n.intValue() > 0 ? 1 : 0);
        return (short) ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()) ? 1 : 0);
    }
}
