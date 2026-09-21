package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ScheduledMessage;
import ak.dev.irc.app.chat.enums.ScheduledMessageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, UUID> {

    /** Due, still-pending scheduled messages — the scheduler's poll query. */
    @Query("""
        SELECT m FROM ScheduledMessage m
        WHERE m.status = :status
          AND m.scheduledAt <= :cutoff
        ORDER BY m.scheduledAt ASC
        """)
    List<ScheduledMessage> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            @Param("status") ScheduledMessageStatus status,
            @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** A user's pending scheduled messages in a conversation (the "Scheduled" tray). */
    @Query("""
        SELECT m FROM ScheduledMessage m
        WHERE m.conversationId = :conversationId
          AND m.senderId = :senderId
          AND m.status = :status
        ORDER BY m.scheduledAt ASC
        """)
    List<ScheduledMessage> findByConversationIdAndSenderIdAndStatusOrderByScheduledAtAsc(
            @Param("conversationId") UUID conversationId, @Param("senderId") UUID senderId,
            @Param("status") ScheduledMessageStatus status);

    /** Whole-conversation purge (ConversationPurgeJob) only — a queued send-later
     *  into a purged thread must die with it, not fire into a 404. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ScheduledMessage m WHERE m.conversationId = :cid")
    int deleteAllForConversation(@Param("cid") UUID conversationId);

    /** Retire veto: a DM with a queued send-later is not "fully walked away
     *  from" — retiring it would strand the send as an invisible FAILURE. */
    boolean existsByConversationIdAndStatus(UUID conversationId, ScheduledMessageStatus status);
}
