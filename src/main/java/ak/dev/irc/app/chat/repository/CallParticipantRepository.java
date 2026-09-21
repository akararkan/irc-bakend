package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, UUID> {

    List<CallParticipant> findByCallId(UUID callId);

    Optional<CallParticipant> findByCallIdAndUserId(UUID callId, UUID userId);

    /** Id-only projection to (re)prime the signal route cache without hydrating entities. */
    @Query("SELECT p.userId FROM CallParticipant p WHERE p.callId = :callId")
    List<UUID> findUserIdsByCallId(@Param("callId") UUID callId);

    /** Whole-conversation purge (ConversationPurgeJob) only — participants of the
     *  purged conversation's call sessions, deleted before their sessions. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM CallParticipant p WHERE p.callId IN :callIds")
    int deleteAllForCalls(@Param("callIds") java.util.Collection<UUID> callIds);
}
