package ak.dev.irc.app.moderation.entity;

import ak.dev.irc.app.moderation.enums.ModerationLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * A hand-reviewed regression case every candidate model must still get right
 * before promotion (MODERATION_ROADMAP.md §17, "golden set").
 *
 * <p>Separate from {@link ModerationTrainingExample} on purpose: a golden case is
 * never trained on. Training on it would make the regression suite measure
 * memorisation instead of generalisation, and the suite would stop catching the
 * thing it exists to catch.</p>
 */
@Entity
@Table(name = "moderation_golden_cases")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationGoldenCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "text", columnDefinition = "text", nullable = false)
    private String text;

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

    /** Why this case is in the suite — "leetspeak evasion", "reclaimed slur", … */
    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = LocalDateTime.now();
    }

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
}
