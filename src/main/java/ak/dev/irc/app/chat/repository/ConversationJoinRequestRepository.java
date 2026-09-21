package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ConversationJoinRequest;
import ak.dev.irc.app.chat.enums.JoinRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationJoinRequestRepository
        extends JpaRepository<ConversationJoinRequest, UUID> {

    Optional<ConversationJoinRequest> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    Page<ConversationJoinRequest> findByConversationIdAndStatusOrderByCreatedAtAsc(
            UUID conversationId, JoinRequestStatus status, Pageable pageable);

    boolean existsByConversationIdAndUserIdAndStatus(UUID conversationId, UUID userId, JoinRequestStatus status);

    long countByConversationIdAndStatus(UUID conversationId, JoinRequestStatus status);

    /** Whole-conversation purge (ConversationPurgeJob) only. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ConversationJoinRequest r WHERE r.conversationId = :cid")
    int deleteAllForConversation(@org.springframework.data.repository.query.Param("cid") UUID conversationId);
}
