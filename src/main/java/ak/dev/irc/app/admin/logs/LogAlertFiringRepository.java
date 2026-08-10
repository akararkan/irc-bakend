package ak.dev.irc.app.admin.logs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface LogAlertFiringRepository extends JpaRepository<LogAlertFiring, UUID> {

    @Query(value = """
        SELECT f FROM LogAlertFiring f
        WHERE (CAST(:since AS timestamp) IS NULL OR f.firedAt >= :since)
          AND (:ruleId IS NULL OR f.ruleId = :ruleId)
        ORDER BY f.firedAt DESC
        """,
        countQuery = """
        SELECT COUNT(f) FROM LogAlertFiring f
        WHERE (CAST(:since AS timestamp) IS NULL OR f.firedAt >= :since)
          AND (:ruleId IS NULL OR f.ruleId = :ruleId)
        """)
    Page<LogAlertFiring> feed(@Param("since") LocalDateTime since,
                              @Param("ruleId") UUID ruleId,
                              Pageable pageable);

    /** Dedup probe so a persisting condition doesn't refire every sweep tick. */
    @Query("""
        SELECT COUNT(f) FROM LogAlertFiring f
        WHERE f.dedupKey = :dedupKey AND f.firedAt >= :since
        """)
    long countRecentByDedup(@Param("dedupKey") String dedupKey,
                            @Param("since") LocalDateTime since);
}
