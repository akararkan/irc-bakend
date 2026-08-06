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
}
