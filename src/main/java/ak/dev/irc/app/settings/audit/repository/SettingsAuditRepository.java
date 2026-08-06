package ak.dev.irc.app.settings.audit.repository;

import ak.dev.irc.app.settings.audit.entity.SettingsAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SettingsAuditRepository extends JpaRepository<SettingsAudit, UUID> {

    Page<SettingsAudit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // ── Log Explorer + retention + GDPR purge (logs-audit.md §4/§7/§8) ────

    /** Anchor-free time-range explore page (index-backed: idx (user_id, created_at) + PK order). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT a FROM SettingsAudit a
        WHERE (:userId IS NULL OR a.userId = :userId)
          AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR a.createdAt <= :to)
        ORDER BY a.createdAt DESC
        """)
    Page<SettingsAudit> explore(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
                                Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM SettingsAudit a WHERE a.userId = :userId")
    int purgeForUser(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM SettingsAudit a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
