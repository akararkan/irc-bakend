package ak.dev.irc.app.settings.safety.repository;

import ak.dev.irc.app.settings.safety.entity.UserStrike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserStrikeRepository extends JpaRepository<UserStrike, UUID> {

    @Query("SELECT s FROM UserStrike s WHERE s.userId = :userId ORDER BY s.issuedAt DESC")
    List<UserStrike> findByUserIdOrderByIssuedAtDesc(@Param("userId") UUID userId);

    @Query("""
        SELECT COUNT(s) FROM UserStrike s
        WHERE s.userId = :userId AND s.expiresAt > :now
        """)
    long countActive(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    // ── Admin ledger (safety-reports.md §3.8/§5) ──────────────────────────

    @Query("SELECT s FROM UserStrike s WHERE s.reportId = :reportId")
    java.util.List<UserStrike> findByReportId(@Param("reportId") UUID reportId);

    @Query(value = "SELECT s FROM UserStrike s ORDER BY s.issuedAt DESC",
           countQuery = "SELECT COUNT(s) FROM UserStrike s")
    org.springframework.data.domain.Page<UserStrike> findAllByOrderByIssuedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    @Query(value = "SELECT s FROM UserStrike s WHERE s.userId = :userId ORDER BY s.issuedAt DESC",
           countQuery = "SELECT COUNT(s) FROM UserStrike s WHERE s.userId = :userId")
    org.springframework.data.domain.Page<UserStrike> findByUserIdOrderByIssuedAtDesc(
            @Param("userId") UUID userId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COUNT(s) FROM UserStrike s WHERE s.expiresAt > :now")
    long countAllActive(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(s) FROM UserStrike s WHERE s.issuedAt >= :from")
    long countIssuedSince(@Param("from") LocalDateTime from);
}
