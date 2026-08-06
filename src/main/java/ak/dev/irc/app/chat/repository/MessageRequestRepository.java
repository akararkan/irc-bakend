package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.MessageRequest;
import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRequestRepository extends JpaRepository<MessageRequest, UUID> {

    Optional<MessageRequest> findByRecipientIdAndRequesterId(UUID recipientId, UUID requesterId);

    Optional<MessageRequest> findByConversationId(UUID conversationId);

    @Query("""
        SELECT r FROM MessageRequest r
        WHERE r.recipientId = :uid AND r.status = :status
        ORDER BY r.createdAt DESC
        """)
    Page<MessageRequest> findInbox(@Param("uid") UUID recipientId,
                                   @Param("status") MessageRequestStatus status,
                                   Pageable pageable);

    long countByRecipientIdAndStatus(UUID recipientId, MessageRequestStatus status);

    @Modifying
    @Query("UPDATE MessageRequest r SET r.messageCount = r.messageCount + 1 WHERE r.id = :id")
    void incrementMessageCount(@Param("id") UUID id);

    // ── Admin quarantine stats (chat-channels-live.md §5.2) ───────────────

    @Query("SELECT COUNT(r) FROM MessageRequest r WHERE r.status = :status")
    long countByStatus(@Param("status") ak.dev.irc.app.chat.enums.MessageRequestStatus status);

    @Query("""
        SELECT r.status, COUNT(r) FROM MessageRequest r
        WHERE r.createdAt >= :from AND r.createdAt <= :to
        GROUP BY r.status
        """)
    java.util.List<Object[]> countByStatusBetween(@Param("from") java.time.LocalDateTime from,
                                                  @Param("to") java.time.LocalDateTime to);

    /** Spam signal: requesters BLOCKED by many distinct recipients. */
    @Query(value = """
        SELECT r.requester_id, COUNT(DISTINCT r.recipient_id) AS blocked_by
        FROM message_requests r
        WHERE CAST(r.status AS text) = 'BLOCKED'
        GROUP BY r.requester_id
        ORDER BY blocked_by DESC
        LIMIT :limit
        """, nativeQuery = true)
    java.util.List<Object[]> topBlockedRequesters(@Param("limit") int limit);
}
