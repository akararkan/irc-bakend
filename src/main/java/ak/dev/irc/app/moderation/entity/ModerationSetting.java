package ak.dev.irc.app.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
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
 * One admin-tunable moderation knob. This is the table MODERATION_ROADMAP.md
 * §8.1 insists on: thresholds and hold durations live in the database, not in
 * {@code application.yaml}, so sensitivity can be retuned from the dashboard
 * without redeploying either service.
 *
 * <p>Key/value rather than the singleton-typed-row shape used by
 * {@code feed_ranking_config}, because the keyspace is genuinely open-ended:
 * six labels × two bands × thirteen entity types is already 150+ possible
 * overrides, and a column per knob would be unmaintainable. The trade-off is
 * that validation lives in {@code ModerationSettingsService} instead of in the
 * schema, so every write goes through it.</p>
 *
 * <p>A row exists only when an admin has overridden the bootstrap default from
 * {@code ModerationProperties}. Absent key = inherit.</p>
 */
@Entity
@Table(name = "moderation_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationSetting {

    /**
     * Dotted key, e.g. {@code threshold.threat.high},
     * {@code threshold.post.insult.low}, {@code hold.post.ms},
     * {@code fallback.story}, {@code enabled.chat_message}.
     */
    @Id
    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 200)
    private String settingValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = LocalDateTime.now();
    }
}
