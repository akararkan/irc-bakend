package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.entity.ConversationMemberId;
import ak.dev.irc.app.chat.enums.MemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMemberRepository
        extends JpaRepository<ConversationMember, ConversationMemberId> {

    @Query("""
        SELECT m FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND m.id.userId = :uid
        """)
    Optional<ConversationMember> findMember(@Param("cid") UUID conversationId,
                                            @Param("uid") UUID userId);

    /**
     * The inbox: my conversations, pinned first then newest activity. The
     * single-valued {@code JOIN FETCH m.conversation} hydrates the preview in one
     * round-trip (no N+1, no in-memory pagination since it is not a collection).
     *
     * <p>Conversations where I am the recipient of a still-PENDING message request
     * are excluded here — they live in the separate Requests inbox until I accept.
     * The requester keeps seeing the thread in their normal inbox.</p>
     */
    @Query(value = """
        SELECT m FROM ConversationMember m
        JOIN FETCH m.conversation c
        WHERE m.id.userId = :uid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.archived = false
          AND c.deletedAt IS NULL
          AND (m.clearedBeforeMessageId = 0
               OR (c.lastMessageId IS NOT NULL AND c.lastMessageId > m.clearedBeforeMessageId))
          AND NOT EXISTS (
              SELECT 1 FROM MessageRequest r
              WHERE r.conversationId = c.id
                AND r.recipientId = :uid
                AND r.status = ak.dev.irc.app.chat.enums.MessageRequestStatus.PENDING)
        ORDER BY m.pinned DESC, c.lastMessageAt DESC NULLS LAST
        """,
        countQuery = """
        SELECT COUNT(m) FROM ConversationMember m
        WHERE m.id.userId = :uid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.archived = false
          AND m.conversation.deletedAt IS NULL
          AND (m.clearedBeforeMessageId = 0
               OR (m.conversation.lastMessageId IS NOT NULL
                   AND m.conversation.lastMessageId > m.clearedBeforeMessageId))
          AND NOT EXISTS (
              SELECT 1 FROM MessageRequest r
              WHERE r.conversationId = m.conversation.id
                AND r.recipientId = :uid
                AND r.status = ak.dev.irc.app.chat.enums.MessageRequestStatus.PENDING)
        """)
    Page<ConversationMember> findInbox(@Param("uid") UUID userId, Pageable pageable);

    @Query(value = """
        SELECT m FROM ConversationMember m
        JOIN FETCH m.conversation c
        WHERE m.id.userId = :uid AND m.archived = true
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND c.deletedAt IS NULL
          AND (m.clearedBeforeMessageId = 0
               OR (c.lastMessageId IS NOT NULL AND c.lastMessageId > m.clearedBeforeMessageId))
        ORDER BY c.lastMessageAt DESC NULLS LAST
        """,
        countQuery = """
        SELECT COUNT(m) FROM ConversationMember m
        WHERE m.id.userId = :uid AND m.archived = true
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.conversation.deletedAt IS NULL
          AND (m.clearedBeforeMessageId = 0
               OR (m.conversation.lastMessageId IS NOT NULL
                   AND m.conversation.lastMessageId > m.clearedBeforeMessageId))
        """)
    Page<ConversationMember> findArchived(@Param("uid") UUID userId, Pageable pageable);

    /** All members of a conversation (member-list endpoint). */
    @Query("SELECT m FROM ConversationMember m WHERE m.id.conversationId = :cid")
    Page<ConversationMember> findByConversation(@Param("cid") UUID conversationId, Pageable pageable);

    /**
     * The "other" members of a set of conversations — used to resolve DIRECT peers
     * for the inbox in one round-trip (each DIRECT yields exactly one row).
     */
    @Query("""
        SELECT m FROM ConversationMember m
        WHERE m.id.conversationId IN :cids AND m.id.userId <> :me
        """)
    List<ConversationMember> findPeers(@Param("cids") java.util.Collection<UUID> conversationIds,
                                       @Param("me") UUID me);

    @Query("SELECT m FROM ConversationMember m WHERE m.id.conversationId = :cid")
    List<ConversationMember> findAllByConversation(@Param("cid") UUID conversationId);

    /** Membership rows of ONE conversation for a set of users — the batch
     *  existence check for group add (replaces a per-candidate point read). */
    @Query("""
        SELECT m FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND m.id.userId IN :uids
        """)
    List<ConversationMember> findMembersIn(@Param("cid") UUID conversationId,
                                           @Param("uids") java.util.Collection<UUID> userIds);

    /** My membership rows across a set of conversations (for per-conversation
     *  history-floor resolution in cross-conversation search). */
    @Query("""
        SELECT m FROM ConversationMember m
        WHERE m.id.userId = :uid AND m.id.conversationId IN :cids
        """)
    List<ConversationMember> findMyMembershipsIn(@Param("uid") UUID userId,
                                                 @Param("cids") java.util.Collection<UUID> conversationIds);

    /** Advance a single member's OWN read marker (used for the sender on send, so a
     *  user is never shown as unread on their own latest message). Only moves forward. */
    @Modifying
    @Query("""
        UPDATE ConversationMember m SET m.lastReadMessageId = :mid, m.unreadCount = 0, m.markedUnread = false
         WHERE m.id.conversationId = :cid AND m.id.userId = :uid AND m.lastReadMessageId < :mid
        """)
    int advanceOwnMarker(@Param("cid") UUID conversationId, @Param("uid") UUID userId, @Param("mid") long messageId);

    /** Advance a member's DELIVERED marker (device received the message). Only moves forward. */
    @Modifying
    @Query("""
        UPDATE ConversationMember m SET m.lastDeliveredMessageId = :mid
         WHERE m.id.conversationId = :cid AND m.id.userId = :uid AND m.lastDeliveredMessageId < :mid
        """)
    int advanceDeliveredMarker(@Param("cid") UUID conversationId, @Param("uid") UUID userId, @Param("mid") long messageId);

    /** Set the explicit "marked as unread" flag on my membership. */
    @Modifying
    @Query("UPDATE ConversationMember m SET m.markedUnread = :flag WHERE m.id.conversationId = :cid AND m.id.userId = :uid")
    int setMarkedUnread(@Param("cid") UUID conversationId, @Param("uid") UUID userId, @Param("flag") boolean flag);

    /** Members who have SEEN (read marker ≥) a message — the group "seen by" set. */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND m.lastReadMessageId >= :messageId
          AND m.id.userId <> :exclude
        """)
    List<UUID> findSeenBy(@Param("cid") UUID conversationId,
                          @Param("messageId") long messageId,
                          @Param("exclude") UUID exclude);

    /** Conversation ids the user can read — the membership scope for cross-conversation search. */
    @Query("""
        SELECT m.id.conversationId FROM ConversationMember m
        WHERE m.id.userId = :uid
          AND (m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
               OR m.status = ak.dev.irc.app.chat.enums.MemberStatus.RESTRICTED)
        """)
    List<UUID> findMyConversationIds(@Param("uid") UUID userId);

    /** Active member ids — the eager unread-fanout + realtime recipient set. */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
        """)
    List<UUID> findActiveMemberIds(@Param("cid") UUID conversationId);

    /** Members who can read (ACTIVE or RESTRICTED) — realtime recipients for edits/deletes. */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND (m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
               OR m.status = ak.dev.irc.app.chat.enums.MemberStatus.RESTRICTED)
        """)
    List<UUID> findReadableMemberIds(@Param("cid") UUID conversationId);

    long countByIdConversationIdAndStatus(UUID conversationId, MemberStatus status);

    /**
     * Eager unread fan-out for small conversations: one bulk write bumps every
     * active member except the sender. Skipped above the large-group cutoff where
     * unread is computed lazily instead.
     */
    @Modifying
    @Query("""
        UPDATE ConversationMember m
           SET m.unreadCount = m.unreadCount + 1
         WHERE m.id.conversationId = :cid
           AND m.id.userId <> :sender
           AND (m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
                OR m.status = ak.dev.irc.app.chat.enums.MemberStatus.RESTRICTED)
        """)
    int bumpUnreadForOthers(@Param("cid") UUID conversationId, @Param("sender") UUID senderId);

    /** "Read by" for a message in a group: members whose marker has reached it. */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND m.lastReadMessageId >= :messageId
          AND m.id.userId <> :exclude
        """)
    List<UUID> findReadersOf(@Param("cid") UUID conversationId,
                             @Param("messageId") long messageId,
                             @Param("exclude") UUID exclude);

    /** Sum of unread across all my active conversations — the badge rebuild. */
    @Query("""
        SELECT COALESCE(SUM(m.unreadCount), 0) FROM ConversationMember m
        WHERE m.id.userId = :uid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
        """)
    long sumUnread(@Param("uid") UUID userId);

    /** Active members currently muting the conversation — excluded from bell
     *  notifications (mute suppresses push but NOT the unread count). */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.mutedUntil IS NOT NULL AND m.mutedUntil > CURRENT_TIMESTAMP
        """)
    List<UUID> findCurrentlyMutedIds(@Param("cid") UUID conversationId);

    /**
     * Keyset page of bell-notifiable member ids: ACTIVE and not currently
     * muted, ordered by user id. Same shape as
     * {@code UserFollowRepository.findFollowerIdsAfter} — constant-time per
     * page at any depth, so a 100k-subscriber channel post fan-out never
     * degrades the way OFFSET paging would.
     */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND (m.mutedUntil IS NULL OR m.mutedUntil <= CURRENT_TIMESTAMP)
          AND (:afterId IS NULL OR m.id.userId > :afterId)
        ORDER BY m.id.userId ASC
        """)
    List<UUID> findNotifiableMemberIdsAfter(@Param("cid") UUID conversationId,
                                            @Param("afterId") UUID afterId,
                                            Pageable pageable);

    // ── Channels ─────────────────────────────────────────────────────────────────

    /** Active owner + admin ids — join-request notifications and admin fan-out. */
    @Query("""
        SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND (m.role = ak.dev.irc.app.chat.enums.MemberRole.OWNER
               OR m.role = ak.dev.irc.app.chat.enums.MemberRole.ADMIN)
        """)
    List<UUID> findStaffIds(@Param("cid") UUID conversationId);

    /** Subscribers who joined since {@code since} (subscriber-growth stats). */
    @Query("""
        SELECT COUNT(m) FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.joinedAt >= :since
        """)
    long countJoinedSince(@Param("cid") UUID conversationId,
                          @Param("since") java.time.LocalDateTime since);

    /** Members who left / were removed since {@code since} (churn stats). */
    @Query("""
        SELECT COUNT(m) FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND (m.status = ak.dev.irc.app.chat.enums.MemberStatus.LEFT
               OR m.status = ak.dev.irc.app.chat.enums.MemberStatus.REMOVED)
          AND m.updatedAt >= :since
        """)
    long countLeftSince(@Param("cid") UUID conversationId,
                        @Param("since") java.time.LocalDateTime since);

    /** Active subscribers currently muting the conversation. */
    @Query("""
        SELECT COUNT(m) FROM ConversationMember m
        WHERE m.id.conversationId = :cid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.mutedUntil IS NOT NULL AND m.mutedUntil > CURRENT_TIMESTAMP
        """)
    long countMuted(@Param("cid") UUID conversationId);

    /** Joins per day since {@code since} — (ISO date string, count) rows. */
    @Query(value = """
        SELECT to_char(m.joined_at, 'YYYY-MM-DD') AS day, COUNT(*) AS joins
          FROM conversation_members m
         WHERE m.conversation_id = :cid AND m.status = 'ACTIVE' AND m.joined_at >= :since
         GROUP BY day ORDER BY day
        """, nativeQuery = true)
    List<Object[]> joinsByDay(@Param("cid") UUID conversationId,
                              @Param("since") java.time.LocalDateTime since);

    /** The CHANNEL conversations this user actively subscribes to — the
     *  channel-story tray scope. */
    @Query("""
        SELECT m.conversation FROM ConversationMember m
        WHERE m.id.userId = :uid
          AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
          AND m.conversation.type = ak.dev.irc.app.chat.enums.ConversationType.CHANNEL
          AND m.conversation.deletedAt IS NULL
        """)
    List<ak.dev.irc.app.chat.entity.Conversation> findMySubscribedChannels(@Param("uid") UUID userId);

    /** Active members per join source — (source, count) rows; legacy null rows
     *  are reported as 'UNKNOWN'. */
    @Query(value = """
        SELECT COALESCE(m.join_source, 'UNKNOWN') AS source, COUNT(*) AS joins
          FROM conversation_members m
         WHERE m.conversation_id = :cid AND m.status = 'ACTIVE'
         GROUP BY source ORDER BY joins DESC
        """, nativeQuery = true)
    List<Object[]> joinsBySource(@Param("cid") UUID conversationId);
}
