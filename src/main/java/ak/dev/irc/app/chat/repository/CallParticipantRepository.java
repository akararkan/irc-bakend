package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, UUID> {

    List<CallParticipant> findByCallId(UUID callId);

    Optional<CallParticipant> findByCallIdAndUserId(UUID callId, UUID userId);
}
