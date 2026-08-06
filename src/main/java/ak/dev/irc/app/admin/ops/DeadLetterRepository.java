package ak.dev.irc.app.admin.ops;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    @Query("""
        SELECT d FROM DeadLetter d
        WHERE (:status IS NULL OR d.status = :status)
          AND (:routingKey IS NULL OR d.routingKey = :routingKey)
        ORDER BY d.receivedAt DESC
        """)
    Page<DeadLetter> browse(@Param("status") DeadLetter.Status status,
                            @Param("routingKey") String routingKey,
                            Pageable pageable);

    @Query("SELECT COUNT(d) FROM DeadLetter d WHERE d.receivedAt >= :since")
    long countArrivedSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(d) FROM DeadLetter d WHERE d.status = :status")
    long countByStatusExact(@Param("status") DeadLetter.Status status);

    /** 90-day retention (logs-audit.md §7 dlq_events recommendation). */
    @Modifying
    @Query("DELETE FROM DeadLetter d WHERE d.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
