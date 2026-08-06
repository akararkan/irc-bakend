package ak.dev.irc.app.security.login.repository;

import ak.dev.irc.app.security.login.entity.LoginEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    @Query("""
        SELECT e FROM LoginEvent e
        WHERE e.userId = :userId
        ORDER BY e.ts DESC
        """)
    Page<LoginEvent> findByUserIdOrderByTsDesc(@Param("userId") UUID userId, Pageable pageable);

    /** Distinct IPs this user has succeeded from — used to spot a new device/location. */
    @Query("""
        SELECT DISTINCT e.ip FROM LoginEvent e
        WHERE e.userId = :userId AND e.outcome = 'SUCCESS' AND e.ip IS NOT NULL
        """)
    List<String> distinctSuccessfulIps(@Param("userId") UUID userId);

    // ── Log Explorer + alert sweeps + retention + GDPR (logs-audit.md) ────

    /** Global reader — every filter optional; feeds the Log Explorer + A4. */
    @Query("""
        SELECT e FROM LoginEvent e
        WHERE (:userId IS NULL OR e.userId = :userId)
          AND (:ip IS NULL OR e.ip = :ip)
          AND (:outcome IS NULL OR e.outcome = :outcome)
          AND (CAST(:from AS timestamp) IS NULL OR e.ts >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR e.ts <= :to)
        ORDER BY e.ts DESC
        """)
    Page<LoginEvent> explore(@Param("userId") UUID userId,
                             @Param("ip") String ip,
                             @Param("outcome") String outcome,
                             @Param("from") java.time.LocalDateTime from,
                             @Param("to") java.time.LocalDateTime to,
                             Pageable pageable);

    /** Failed-login burst per ACCOUNT (alert rule) — index-backed group scan. */
    @Query(value = """
        SELECT e.user_id, COUNT(*) FROM login_events e
        WHERE e.outcome = 'FAILED' AND e.ts >= :since AND e.user_id IS NOT NULL
        GROUP BY e.user_id HAVING COUNT(*) >= :threshold
        """, nativeQuery = true)
    List<Object[]> failedByUserSince(@Param("since") java.time.LocalDateTime since,
                                     @Param("threshold") long threshold);

    /** Failed-login burst per IP (alert rule). */
    @Query(value = """
        SELECT e.ip, COUNT(*) FROM login_events e
        WHERE e.outcome = 'FAILED' AND e.ts >= :since AND e.ip IS NOT NULL
        GROUP BY e.ip HAVING COUNT(*) >= :threshold
        """, nativeQuery = true)
    List<Object[]> failedByIpSince(@Param("since") java.time.LocalDateTime since,
                                   @Param("threshold") long threshold);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM LoginEvent e WHERE e.userId = :userId")
    int purgeForUser(@Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM LoginEvent e WHERE e.ts < :cutoff")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
