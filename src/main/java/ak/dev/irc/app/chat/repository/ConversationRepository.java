package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.ConversationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Race-safe DM lookup — the {@code UNIQUE(direct_key)} constraint is the arbiter. */
    Optional<Conversation> findByDirectKey(String directKey);

    /**
     * Advance the inbox pointer, but only ever forwards. Because message ids are
     * monotonic Snowflakes, {@code last_message_id < :messageId} guarantees a
     * late-arriving concurrent send can't rewind the preview to an older message.
     * Returns rows affected (0 when a newer message already won the race).
     */
    @Modifying
    @Query("""
        UPDATE Conversation c
           SET c.lastMessageId = :messageId,
               c.lastMessageAt = :at,
               c.lastMessagePreview = :preview
         WHERE c.id = :id
           AND (c.lastMessageId IS NULL OR c.lastMessageId < :messageId)
        """)
    int advanceLastMessage(@Param("id") UUID id,
                           @Param("messageId") long messageId,
                           @Param("at") LocalDateTime at,
                           @Param("preview") String preview);

    /**
     * Atomic member-count delta. {@code flushAutomatically} pushes any pending
     * managed-entity changes (e.g. a member status update in the same tx) to the
     * DB first, and {@code clearAutomatically} detaches the persistence context so
     * a subsequent read (the response's {@code conversationService.get(...)}) sees
     * the fresh count rather than a stale cached row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.memberCount = c.memberCount + :delta WHERE c.id = :id")
    void adjustMemberCount(@Param("id") UUID id, @Param("delta") int delta);

    /** Bulk soft-delete — used where the caller also issued a bulk count update in
     *  the same tx, so a full-entity {@code save()} would overwrite it with a stale row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.deletedAt = :at WHERE c.id = :id")
    void softDelete(@Param("id") UUID id, @Param("at") LocalDateTime at);

    // ── Channels ─────────────────────────────────────────────────────────────────

    /** Public channel lookup by @handle. */
    Optional<Conversation> findByHandle(String handle);

    boolean existsByHandle(String handle);

    /** Discover public channels by title/handle (optionally within a category),
     *  most-subscribed first. An empty {@code q} lists all public channels. */
    @Query("""
        SELECT c FROM Conversation c
         WHERE c.type = :type AND c.publicChannel = true AND c.deletedAt IS NULL
           AND (:q = '' OR LOWER(c.title) LIKE LOWER(CONCAT('%', :q, '%'))
                       OR LOWER(c.handle) LIKE LOWER(CONCAT('%', :q, '%')))
           AND (:category IS NULL OR c.category = :category)
         ORDER BY c.memberCount DESC
        """)
    List<Conversation> discoverChannels(@Param("type") ConversationType type,
                                        @Param("q") String q,
                                        @Param("category") String category,
                                        Pageable pageable);

    /** The channel a discussion group belongs to (reverse of {@code linkedGroupId}). */
    Optional<Conversation> findByLinkedGroupId(UUID linkedGroupId);

    /** Atomic post-count delta for channel posts (send +1 / delete −1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.postCount = c.postCount + :delta WHERE c.id = :id AND c.postCount + :delta >= 0")
    void adjustPostCount(@Param("id") UUID id, @Param("delta") int delta);
}
