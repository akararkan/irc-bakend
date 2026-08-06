package ak.dev.irc.app.admin.ops;

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
import java.util.UUID;

/**
 * The {@code job_runs} ledger (operations.md §4.4): before this, no job
 * persisted any run record — "did last night's purge run?" was unanswerable
 * without console access. One row per business-job execution (SSE heartbeat
 * sweeps are deliberately excluded — they'd be noise).
 */
@Entity
@Table(name = "job_runs",
        indexes = {
                @Index(name = "idx_job_run_name", columnList = "job_name, started_at"),
                @Index(name = "idx_job_run_time", columnList = "started_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRun {

    public enum Outcome {RUNNING, SUCCESS, FAILED, PARTIAL, SKIPPED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_name", nullable = false, length = 60)
    private String jobName;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    @Builder.Default
    private Outcome outcome = Outcome.RUNNING;

    @Column(name = "items_processed")
    private Integer itemsProcessed;

    @Column(name = "items_failed")
    private Integer itemsFailed;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "host", length = 120)
    private String host;

    /** Admin id when manually triggered; null for scheduled runs. */
    @Column(name = "triggered_by")
    private UUID triggeredBy;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }
}
