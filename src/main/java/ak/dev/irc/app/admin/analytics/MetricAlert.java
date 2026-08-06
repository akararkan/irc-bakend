package ak.dev.irc.app.admin.analytics;

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

/** One anomaly flag from the z-score scan (analytics-kpis.md §11 {@code metric_alerts}). */
@Entity
@Table(name = "metric_alerts",
        indexes = @Index(name = "idx_metric_alert_day", columnList = "day, metric"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "day", nullable = false, length = 10)
    private String day;

    @Column(name = "metric", nullable = false, length = 80)
    private String metric;

    @Column(name = "z", nullable = false)
    private double z;

    @Column(name = "value", nullable = false)
    private long value;

    @Column(name = "mean", nullable = false)
    private double mean;

    @Column(name = "sd", nullable = false)
    private double sd;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
