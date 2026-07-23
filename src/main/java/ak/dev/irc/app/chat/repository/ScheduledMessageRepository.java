package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ScheduledMessage;
import ak.dev.irc.app.chat.enums.ScheduledMessageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, UUID> {

    /** Due, still-pending scheduled messages — the scheduler's poll query. */
    List<ScheduledMessage> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledMessageStatus status, LocalDateTime cutoff, Pageable pageable);

    /** A user's pending scheduled messages in a conversation (the "Scheduled" tray). */
    List<ScheduledMessage> findByConversationIdAndSenderIdAndStatusOrderByScheduledAtAsc(
            UUID conversationId, UUID senderId, ScheduledMessageStatus status);
}
