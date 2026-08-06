package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.CallSession;
import ak.dev.irc.app.chat.enums.CallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CallSessionRepository extends JpaRepository<CallSession, UUID> {

    /** The current live call in a conversation, if any (one active call per conversation). */
    Optional<CallSession> findFirstByConversationIdAndStatusInOrderByStartedAtDesc(
            UUID conversationId, Collection<CallStatus> statuses);

    /** Ring-timeout sweep: calls still RINGING since before the cutoff. */
    @Query("""
        SELECT c FROM CallSession c
        WHERE c.status = :status
          AND c.startedAt < :cutoff
        """)
    List<CallSession> findByStatusAndStartedAtBefore(@Param("status") CallStatus status,
                                                     @Param("cutoff") Instant cutoff);

    // ── Admin metadata browse + stats (chat-channels-live.md §4.8/§6) ─────

    @Query(value = """
        SELECT c FROM CallSession c
        WHERE (:type IS NULL OR c.type = :type)
          AND (:status IS NULL OR c.status = :status)
          AND (CAST(:from AS timestamp) IS NULL OR c.startedAt >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR c.startedAt <= :to)
        ORDER BY c.startedAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM CallSession c
        WHERE (:type IS NULL OR c.type = :type)
          AND (:status IS NULL OR c.status = :status)
          AND (CAST(:from AS timestamp) IS NULL OR c.startedAt >= :from)
          AND (CAST(:to   AS timestamp) IS NULL OR c.startedAt <= :to)
        """)
    org.springframework.data.domain.Page<CallSession> adminBrowse(
            @Param("type") ak.dev.irc.app.chat.enums.CallType type,
            @Param("status") CallStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
        SELECT c.status, COUNT(c) FROM CallSession c
        WHERE c.startedAt >= :from AND c.startedAt <= :to
        GROUP BY c.status
        """)
    List<Object[]> countByStatusBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
        SELECT c.type, COUNT(c) FROM CallSession c
        WHERE c.startedAt >= :from AND c.startedAt <= :to
        GROUP BY c.type
        """)
    List<Object[]> countByTypeBetween(@Param("from") Instant from, @Param("to") Instant to);
}
