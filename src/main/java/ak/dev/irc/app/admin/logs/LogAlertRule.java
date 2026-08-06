package ak.dev.irc.app.admin.logs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

/**
 * A log alert rule (logs-audit.md §6.2): each row parameterizes one of the
 * built-in rule kinds the 5-minute sweep can actually evaluate from live
 * stores today. Threshold/window are editable; the kind's data source is
 * fixed in {@link LogAlertSweepJob}.
 */
@Entity
@Table(name = "log_alert_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogAlertRule {

    /** The evaluable rule kinds — each maps to a concrete query in the sweep. */
    public enum RuleKind {
        FAILED_LOGIN_PER_ACCOUNT,   // ≥ threshold FAILED login_events / window for one user
        FAILED_LOGIN_PER_IP,        // ≥ threshold FAILED login_events / window from one IP
        REPORT_PILE_ON,             // ≥ threshold reports on one group_key / window
        DLQ_ARRIVALS,               // ≥ threshold parked dead letters / window
        OTP_ABUSE,                  // ≥ threshold OTP challenges / window (volume proxy)
        PURGE_JOB_SILENCE           // no SUCCESS account-purge job_runs row within window
    }

    public enum Severity {INFO, MEDIUM, HIGH}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40)
    private RuleKind kind;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "threshold", nullable = false)
    private long threshold;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
