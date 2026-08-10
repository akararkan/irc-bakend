package ak.dev.irc.app.admin.ops;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    /** Most recent run of one job (rides idx_job_run_name). */
    @Query("SELECT r FROM JobRun r WHERE r.jobName = :jobName ORDER BY r.startedAt DESC LIMIT 1")
    Optional<JobRun> latestRun(@Param("jobName") String jobName);

    /**
     * Most recent run of EVERY job in one index-backed scan — the jobs
     * dashboard wants the latest row per registry entry, not a point query
     * per job.
     */
    @Query(value = """
        SELECT DISTINCT ON (job_name) *
        FROM job_runs
        ORDER BY job_name, started_at DESC
        """, nativeQuery = true)
    List<JobRun> latestRunPerJob();

    @Query(value = "SELECT r FROM JobRun r WHERE r.jobName = :jobName ORDER BY r.startedAt DESC",
           countQuery = "SELECT COUNT(r) FROM JobRun r WHERE r.jobName = :jobName")
    Page<JobRun> runsFor(@Param("jobName") String jobName, Pageable pageable);

    @Modifying
    @Query("DELETE FROM JobRun r WHERE r.startedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
