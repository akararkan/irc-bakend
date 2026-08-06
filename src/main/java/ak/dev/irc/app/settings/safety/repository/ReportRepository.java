package ak.dev.irc.app.settings.safety.repository;

import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    Page<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId, Pageable pageable);

    /** Dedup probe: an already-open report by the same reporter for the same group. */
    Optional<Report> findFirstByReporterIdAndGroupKeyAndStateIn(UUID reporterId,
                                                                String groupKey,
                                                                Collection<ReportState> states);

    // ── Admin triage surface (docs/admin/safety-reports.md §4-5) ──────────

    Page<Report> findByStateInOrderByCreatedAtAsc(Collection<ReportState> states, Pageable pageable);

    Page<Report> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            ak.dev.irc.app.settings.safety.enums.ReportTargetType targetType,
            UUID targetId, Pageable pageable);

    java.util.List<Report> findByGroupKeyOrderByCreatedAtAsc(String groupKey);

    java.util.List<Report> findByGroupKeyAndStateIn(String groupKey, Collection<ReportState> states);

    @Query("SELECT COUNT(r) FROM Report r WHERE r.state IN :states")
    long countByStateIn(@Param("states") Collection<ReportState> states);

    @Query("SELECT COUNT(r) FROM Report r WHERE r.state = :state")
    long countByState(@Param("state") ReportState state);

    /**
     * The grouped triage queue — one row per (targetType, targetId, reason)
     * group with reporter count and age, oldest-first.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT r.group_key,
                   MIN(CAST(r.target_type AS text)) AS target_type,
                   MIN(CAST(r.target_id AS text))   AS target_id,
                   MIN(CAST(r.reason AS text))      AS reason,
                   COUNT(DISTINCT r.reporter_id)    AS reporter_count,
                   COUNT(*)                         AS report_count,
                   MIN(r.created_at)                AS first_seen,
                   MAX(r.created_at)                AS last_seen,
                   MIN(CAST(r.state AS text))       AS state
            FROM reports r
            WHERE CAST(r.state AS text) IN (:states)
              AND (:targetType IS NULL OR CAST(r.target_type AS text) = :targetType)
              AND (:reason IS NULL OR CAST(r.reason AS text) = :reason)
            GROUP BY r.group_key
            ORDER BY MIN(r.created_at) ASC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    java.util.List<Object[]> findOpenGroups(@org.springframework.data.repository.query.Param("states") Collection<String> states,
                                            @org.springframework.data.repository.query.Param("targetType") String targetType,
                                            @org.springframework.data.repository.query.Param("reason") String reason,
                                            @org.springframework.data.repository.query.Param("limit") int limit,
                                            @org.springframework.data.repository.query.Param("offset") int offset);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT CAST(r.reason AS text), COUNT(*) FROM reports r
            WHERE r.created_at >= :from AND r.created_at <= :to
            GROUP BY 1 ORDER BY 2 DESC
            """, nativeQuery = true)
    java.util.List<Object[]> countByReasonBetween(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                                  @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT CAST(r.state AS text), COUNT(*) FROM reports r GROUP BY 1
            """, nativeQuery = true)
    java.util.List<Object[]> countGroupedByState();

    /** Log-Explorer adapter: anchor-free time-range page (idx-backed by created_at order). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM Report r
        WHERE (CAST(:from AS timestamp) IS NULL OR r.createdAt >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR r.createdAt <= :to)
        ORDER BY r.createdAt DESC
        """)
    Page<Report> exploreByTime(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                               @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
                               Pageable pageable);

    /** Pile-on alert rule: groups gaining ≥ threshold reports inside the window. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT r.group_key, COUNT(*) FROM reports r
            WHERE r.created_at >= :since
            GROUP BY r.group_key HAVING COUNT(*) >= :threshold
            """, nativeQuery = true)
    java.util.List<Object[]> pileOnGroupsSince(
            @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
            @org.springframework.data.repository.query.Param("threshold") long threshold);
}
