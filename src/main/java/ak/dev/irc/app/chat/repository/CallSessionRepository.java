package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.CallSession;
import ak.dev.irc.app.chat.enums.CallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
    List<CallSession> findByStatusAndStartedAtBefore(CallStatus status, Instant cutoff);
}
