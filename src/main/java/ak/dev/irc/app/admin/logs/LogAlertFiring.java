package ak.dev.irc.app.admin.logs;

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

/** One alert-rule firing — the Alerts panel feed (logs-audit.md §6.2, A9). */
@Entity
@Table(name = "log_alert_firings",
        indexes = @Index(name = "idx_alert_firing_time", columnList = "fired_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogAlertFiring {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rule_kind", nullable = false, length = 40)
    private String ruleKind;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    /** What fired: subject (user id, ip, group key) + observed vs threshold. */
    @Column(name = "detail", nullable = false, length = 500)
    private String detail;

    /** Dedup key so one condition doesn't refire every sweep tick. */
    @Column(name = "dedup_key", length = 160)
    private String dedupKey;

    @Column(name = "fired_at", nullable = false, updatable = false)
    private LocalDateTime firedAt;

    @PrePersist
    void onCreate() {
        if (firedAt == null) firedAt = LocalDateTime.now();
    }
}
