package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ConversationDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationDraftRepository extends JpaRepository<ConversationDraft, UUID> {

    Optional<ConversationDraft> findByUserIdAndConversationId(UUID userId, UUID conversationId);

    void deleteByUserIdAndConversationId(UUID userId, UUID conversationId);

    /** Whole-conversation purge (ConversationPurgeJob) only. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ConversationDraft d WHERE d.conversationId = :cid")
    int deleteAllForConversation(@org.springframework.data.repository.query.Param("cid") UUID conversationId);
}
