package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ConversationInvite;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationInviteRepository extends JpaRepository<ConversationInvite, UUID> {

    Optional<ConversationInvite> findByTokenHash(String tokenHash);

    List<ConversationInvite> findByConversationIdAndRevokedFalse(UUID conversationId);

    /**
     * Atomically consume one use iff the link is still usable (not revoked, not
     * expired, under {@code maxUses}). Returns rows affected: 1 = consumed,
     * 0 = the link was exhausted/expired/revoked (join must be rejected). This
     * closes the check-then-increment race that {@code isUsable()} alone leaves open.
     */
    @Modifying
    @Query("""
        UPDATE ConversationInvite i SET i.useCount = i.useCount + 1
         WHERE i.id = :id
           AND i.revoked = false
           AND (i.expiresAt IS NULL OR i.expiresAt > CURRENT_TIMESTAMP)
           AND (i.maxUses IS NULL OR i.useCount < i.maxUses)
        """)
    int consumeUse(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE ConversationInvite i SET i.revoked = true WHERE i.conversationId = :cid AND i.revoked = false")
    void revokeAllForConversation(@Param("cid") UUID conversationId);
}
