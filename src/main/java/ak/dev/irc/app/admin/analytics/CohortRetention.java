package ak.dev.irc.app.admin.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One retention-grid cell (analytics-kpis.md §4.4/§6.4
 * {@code cohort_retention_weekly}): of the users who signed up in
 * {@code cohortWeek}, how many were active in week {@code weekOffset}.
 * Recomputed idempotently by {@code WeeklyCohortJob} (unique cell upsert).
 */
@Entity
@Table(name = "cohort_retention_weekly",
        uniqueConstraints = @UniqueConstraint(name = "uq_cohort_cell",
                columnNames = {"cohort_week", "week_offset"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortRetention {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** ISO Monday of the signup week, e.g. 2026-07-27. */
    @Column(name = "cohort_week", nullable = false, length = 10)
    private String cohortWeek;

    @Column(name = "week_offset", nullable = false)
    private int weekOffset;

    @Column(name = "cohort_size", nullable = false)
    private long cohortSize;

    @Column(name = "active_count", nullable = false)
    private long activeCount;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
