package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.ConversationType;
import org.springframework.data.domain.Page;
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
     * Swap the inbox preview of a message that already owns the pointer — the
     * automated-moderation release path, where a placeholder was written at hold
     * time and {@link #advanceLastMessage} would refuse to move sideways. The
     * {@code last_message_id = :messageId} guard makes it a no-op once a newer
     * message has taken the pointer.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Conversation c
           SET c.lastMessagePreview = :preview
         WHERE c.id = :id AND c.lastMessageId = :messageId
        """)
    int refreshLastMessagePreview(@Param("id") UUID id,
                                  @Param("messageId") long messageId,
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
     *  most-subscribed first. An empty {@code q} lists all public channels.
     *  Channels whose title/description has not cleared automated moderation are
     *  excluded — this is the fallback path when the ES index is cold, and it has
     *  to enforce the same gate the indexer does. */
    @Query("""
        SELECT c FROM Conversation c
         WHERE c.type = :type AND c.publicChannel = true AND c.deletedAt IS NULL
           AND (c.moderationStatus IS NULL OR c.moderationStatus = 'APPROVED')
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

    /** All live public channels — feeds the {@code irc-channels} ES reindex. */
    Page<Conversation> findByTypeAndPublicChannelTrueAndDeletedAtIsNull(
            ConversationType type, Pageable pageable);

    /** Atomic post-count delta for channel posts (send +1 / delete −1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Conversation c SET c.postCount = c.postCount + :delta WHERE c.id = :id AND c.postCount + :delta >= 0")
    void adjustPostCount(@Param("id") UUID id, @Param("delta") int delta);

    // ── Admin directory (docs/admin/chat-channels-live.md §6) ─────────────
    // Metadata browse: projections built from these NEVER include
    // last_message_preview (the known metadata→content leak edge).

    @Query(value = """
        SELECT c FROM Conversation c
        WHERE (:type IS NULL OR c.type = :type)
          AND (:q IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR LOWER(c.handle) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND (:verified IS NULL OR c.verified = :verified)
          AND (:publicOnly IS NULL OR c.publicChannel = :publicOnly)
          AND (:category IS NULL OR c.category = :category)
          AND (:includeDeleted = TRUE OR c.deletedAt IS NULL)
          AND (:ownerId IS NULL OR c.ownerId = :ownerId)
        ORDER BY c.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM Conversation c
        WHERE (:type IS NULL OR c.type = :type)
          AND (:q IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR LOWER(c.handle) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND (:verified IS NULL OR c.verified = :verified)
          AND (:publicOnly IS NULL OR c.publicChannel = :publicOnly)
          AND (:category IS NULL OR c.category = :category)
          AND (:includeDeleted = TRUE OR c.deletedAt IS NULL)
          AND (:ownerId IS NULL OR c.ownerId = :ownerId)
        """)
    Page<Conversation> adminBrowse(@Param("type") ConversationType type,
                                   @Param("q") String q,
                                   @Param("verified") Boolean verified,
                                   @Param("publicOnly") Boolean publicOnly,
                                   @Param("category") String category,
                                   @Param("includeDeleted") boolean includeDeleted,
                                   @Param("ownerId") UUID ownerId,
                                   Pageable pageable);

    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.type = :type AND c.deletedAt IS NULL")
    long countByTypeAndDeletedAtIsNull(@Param("type") ConversationType type);

    long countByTypeAndVerifiedTrueAndDeletedAtIsNull(ConversationType type);
}
