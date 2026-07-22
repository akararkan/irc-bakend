package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ConversationPin;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationPinRepository extends JpaRepository<ConversationPin, UUID> {

    Optional<ConversationPin> findByConversationIdAndMessageId(UUID conversationId, long messageId);

    /** Pinned messages of a conversation, newest pin first. */
    List<ConversationPin> findByConversationIdOrderByPinnedAtDesc(UUID conversationId);

    long countByConversationId(UUID conversationId);

    @Modifying
    @Query("DELETE FROM ConversationPin p WHERE p.conversationId = :cid AND p.messageId = :mid")
    int deletePin(@Param("cid") UUID conversationId, @Param("mid") long messageId);
}
