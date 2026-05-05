package ak.dev.irc.app.audit.repository;

import ak.dev.irc.app.audit.entity.AuditLog;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Single flexible query for the admin search UI — every filter is
     * nullable so the same method covers "all logs", "by user", "by
     * resource", "in last hour", etc.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:userId       IS NULL OR a.userId       = :userId)
          AND (:operation    IS NULL OR a.operation    = :operation)
          AND (:outcome      IS NULL OR a.outcome      = :outcome)
          AND (:resourceType IS NULL OR a.resourceType = :resourceType)
          AND (:resourceId   IS NULL OR a.resourceId   = :resourceId)
          AND (:from         IS NULL OR a.createdAt   >= :from)
          AND (:to           IS NULL OR a.createdAt   <= :to)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLog> search(@Param("userId")       UUID userId,
                          @Param("operation")    AuditOperation operation,
                          @Param("outcome")      AuditOutcome outcome,
                          @Param("resourceType") String resourceType,
                          @Param("resourceId")   UUID resourceId,
                          @Param("from")         LocalDateTime from,
                          @Param("to")           LocalDateTime to,
                          Pageable pageable);

    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
