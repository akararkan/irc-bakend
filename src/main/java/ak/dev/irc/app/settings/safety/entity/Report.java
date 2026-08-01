package ak.dev.irc.app.settings.safety.entity;

import ak.dev.irc.app.settings.safety.enums.ReportReason;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.enums.ReportTargetType;
import ak.dev.irc.app.settings.safety.enums.Resolution;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user report moving through the moderation state machine (spec §18).
 *
 * <p>{@code groupKey = targetId + ":" + reason} exists so duplicate reports
 * against the same target/reason group into one moderator queue item, and so a
 * single reporter cannot spam-create the same report repeatedly.</p>
 */
@Entity
@Table(name = "reports",
       indexes = {
           @Index(name = "idx_report_reporter", columnList = "reporter_id, created_at"),
           @Index(name = "idx_report_target",   columnList = "target_id, reason"),
           @Index(name = "idx_report_group",    columnList = "group_key")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24)
    private ReportReason reason;

    @Column(name = "details", length = 1000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    @Builder.Default
    private ReportState state = ReportState.SUBMITTED;

    /** The target's private moderation record — never surfaced to the reporter. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 24)
    @Builder.Default
    private Resolution resolution = Resolution.NONE;

    /** {@code targetId:reason} — dedup / grouping key. */
    @Column(name = "group_key", nullable = false, length = 80)
    private String groupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
