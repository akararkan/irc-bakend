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

    /**
     * Single-round-trip fast path for the hot {@code signal()} relay: confirms in one
     * query that (a) the call exists and is active ({@code status IN :activeStatuses}),
     * (b) {@code fromUserId} has a participant row for this call, and (c) {@code toUserId}
     * has a participant row for this call — mirroring {@code requireActiveCall} +
     * {@code requireInvitee} + the recipient {@code findByCallIdAndUserId} lookup exactly,
     * including that participant existence is state-agnostic (any {@code CallParticipant}
     * row counts, not just JOINED). Returns the call's conversationId when all three hold;
     * empty otherwise, in which case the caller must fall back to the three granular checks
     * to surface the specific failure (call missing, call not active, fromUser not an
     * invitee, or toUser not a participant).
     */
    @Query("""
        SELECT c.conversationId FROM CallSession c
        WHERE c.id = :callId
          AND c.status IN :activeStatuses
          AND EXISTS (SELECT 1 FROM CallParticipant p WHERE p.callId = c.id AND p.userId = :fromUserId)
          AND EXISTS (SELECT 1 FROM CallParticipant p WHERE p.callId = c.id AND p.userId = :toUserId)
        """)
    Optional<UUID> findConversationIdForActiveSignal(@Param("callId") UUID callId,
                                                       @Param("fromUserId") UUID fromUserId,
                                                       @Param("toUserId") UUID toUserId,
                                                       @Param("activeStatuses") Collection<CallStatus> activeStatuses);

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

    /** Whole-conversation purge (ConversationPurgeJob): the session ids whose
     *  participant rows must go first (CallParticipant carries only callId). */
    @Query("SELECT c.id FROM CallSession c WHERE c.conversationId = :cid")
    List<UUID> findIdsByConversationId(@Param("cid") UUID conversationId);

    /** Whole-conversation purge (ConversationPurgeJob) only. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM CallSession c WHERE c.conversationId = :cid")
    int deleteAllForConversation(@Param("cid") UUID conversationId);
}
