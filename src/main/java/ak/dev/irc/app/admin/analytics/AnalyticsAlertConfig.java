package ak.dev.irc.app.admin.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-metric anomaly thresholds (analytics-kpis.md §9/§11 — editable defaults). */
@Entity
@Table(name = "analytics_alert_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsAlertConfig {

    @Id
    @Column(name = "metric", nullable = false, length = 80)
    private String metric;

    @Column(name = "z_warn", nullable = false)
    @Builder.Default
    private double zWarn = 2.5;

    @Column(name = "z_alert", nullable = false)
    @Builder.Default
    private double zAlert = 3.5;

    /** Metrics below this daily mean are skipped (small numbers → useless z). */
    @Column(name = "min_volume", nullable = false)
    @Builder.Default
    private long minVolume = 50;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
