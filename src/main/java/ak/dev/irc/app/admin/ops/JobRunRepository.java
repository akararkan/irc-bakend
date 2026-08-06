package ak.dev.irc.app.admin.ops;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    Optional<JobRun> findFirstByJobNameOrderByStartedAtDesc(String jobName);

    @Query("SELECT r FROM JobRun r WHERE r.jobName = :jobName ORDER BY r.startedAt DESC")
    Page<JobRun> findByJobNameOrderByStartedAtDesc(@Param("jobName") String jobName, Pageable pageable);

    @Modifying
    @Query("DELETE FROM JobRun r WHERE r.startedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
