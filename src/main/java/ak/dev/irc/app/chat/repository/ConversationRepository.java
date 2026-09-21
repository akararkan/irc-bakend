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

    // ── Hard-delete purge (ConversationPurgeJob) ─────────────────────────────────

    /**
     * DIRECT conversations that EVERY member has deleted-for-me AND floored at or
     * past the newest message — nobody can see a single row, so the thread is a
     * hard-delete candidate. Phase 1 of the purge job "retires" these by stamping
     * {@code deletedAt}; the {@code EXISTS} guard keeps a memberless orphan row
     * from qualifying vacuously.
     */
    @Query("""
        SELECT c.id FROM Conversation c
        WHERE c.type = ak.dev.irc.app.chat.enums.ConversationType.DIRECT
          AND c.deletedAt IS NULL
          AND EXISTS (
              SELECT 1 FROM ConversationMember any_m
              WHERE any_m.id.conversationId = c.id)
          AND NOT EXISTS (
              SELECT 1 FROM ConversationMember m
              WHERE m.id.conversationId = c.id
                AND (m.deletedAt IS NULL
                     OR (c.lastMessageId IS NOT NULL AND c.lastMessageId > m.clearedBeforeMessageId)))
          AND NOT EXISTS (
              SELECT 1 FROM ScheduledMessage s
              WHERE s.conversationId = c.id
                AND s.status = ak.dev.irc.app.chat.enums.ScheduledMessageStatus.PENDING)
        """)
    List<UUID> findFullyDeletedDirectIds(Pageable pageable);

    /** Retired DMs whose grace has elapsed — the storage-purge slice, oldest first. */
    @Query("""
        SELECT c FROM Conversation c
        WHERE c.type = ak.dev.irc.app.chat.enums.ConversationType.DIRECT
          AND c.deletedAt IS NOT NULL AND c.deletedAt < :cutoff
        ORDER BY c.deletedAt ASC
        """)
    List<Conversation> findRetiredDirectsBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** Owner-deleted GROUPs/CHANNELs whose grace has elapsed, oldest first. */
    @Query("""
        SELECT c FROM Conversation c
        WHERE c.type <> ak.dev.irc.app.chat.enums.ConversationType.DIRECT
          AND c.deletedAt IS NOT NULL AND c.deletedAt < :cutoff
        ORDER BY c.deletedAt ASC
        """)
    List<Conversation> findRetiredRoomsBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** Guarded retire (phase-1 stamp): the eligibility was re-checked in this
     *  transaction; the {@code deletedAt IS NULL} guard closes the last window
     *  against a concurrent owner delete taking the same row. */
    @Modifying
    @Query("UPDATE Conversation c SET c.deletedAt = :at WHERE c.id = :id AND c.deletedAt IS NULL")
    int retire(@Param("id") UUID id, @Param("at") LocalDateTime at);

    /**
     * Un-retire a DM. Two callers: {@code createDirect} reviving a
     * both-sides-deleted thread someone wants to reopen (both floors still hide
     * the old history, so it reopens empty), and the purge job stepping back from
     * a retired DM that turned out to be resurrect-eligible. DIRECT-only by
     * construction — an owner-deleted group/channel must never come back.
     * {@code @Transactional} on the method because createDirect is deliberately
     * non-transactional (race-catch), and a {@code @Modifying} query needs a
     * transaction of its own.
     *
     * <p>The {@code directKey IS NOT NULL} arm is the purge fence: the moment
     * the purge commits to a conversation it nulls the key under a row lock
     * (see {@code ConversationPurgeService.beginPurge}), after which this
     * update matches nothing — a concurrent {@code createDirect} then falls
     * through to a fresh conversation on the freed key instead of reviving a
     * thread whose storage is being irreversibly destroyed.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Conversation c SET c.deletedAt = null
        WHERE c.id = :id
          AND c.type = ak.dev.irc.app.chat.enums.ConversationType.DIRECT
          AND c.deletedAt IS NOT NULL
          AND c.directKey IS NOT NULL
        """)
    int reviveRetiredDirect(@Param("id") UUID id);

    /**
     * Pessimistic hold on one row for the purge's final transaction: the child
     * deletes and the row delete must not interleave with a concurrent
     * {@link #reviveRetiredDirect} — the lock makes the revive wait, observe the
     * deletion, update 0 rows, and fall through to a fresh create.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> lockById(@Param("id") UUID id);

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

    /** Live totals for every type in one scan (admin overview tiles). */
    @Query("SELECT c.type, COUNT(c) FROM Conversation c WHERE c.deletedAt IS NULL GROUP BY c.type")
    java.util.List<Object[]> countLiveGroupedByType();

    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.type = :type AND c.verified = TRUE AND c.deletedAt IS NULL")
    long countByTypeAndVerifiedTrueAndDeletedAtIsNull(@Param("type") ConversationType type);
}
