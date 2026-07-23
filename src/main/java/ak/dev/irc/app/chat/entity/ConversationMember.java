package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Membership + per-user read/inbox state for a conversation. Kept in one row
 * (rather than split across a separate read-state table) so the inbox join and
 * the "advance read marker / bump unread" writes stay single-table.
 *
 * <p>The {@link Conversation} is mapped via {@code @MapsId} so the inbox query
 * can {@code JOIN FETCH} it in one round-trip; the {@code user_id} half of the
 * key is a plain column (the {@code User} entity is never needed for a member
 * row — only its id).</p>
 */
@Entity
@Table(
    name = "conversation_members",
    indexes = {
        // The inbox query: "my conversations, newest first" scans by user_id.
        @Index(name = "idx_member_inbox", columnList = "user_id, archived"),
        @Index(name = "idx_member_conversation", columnList = "conversation_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMember extends BaseAuditEntity {

    @EmbeddedId
    private ConversationMemberId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("conversationId")
    @JoinColumn(name = "conversation_id",
                foreignKey = @ForeignKey(name = "fk_member_conversation"))
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 8)
    @Builder.Default
    private MemberRole role = MemberRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private MemberStatus status = MemberStatus.ACTIVE;

    /** High-water read marker — Snowflake id of the last message this user read. */
    @Column(name = "last_read_message_id", nullable = false)
    @Builder.Default
    private long lastReadMessageId = 0L;

    /** High-water DELIVERED marker — Snowflake id of the last message that reached
     *  this user's device (advanced by {@code /delivered}). Drives the DM double-tick. */
    @Column(name = "last_delivered_message_id", nullable = false,
            columnDefinition = "bigint not null default 0")
    @Builder.Default
    private long lastDeliveredMessageId = 0L;

    @Column(name = "unread_count", nullable = false)
    @Builder.Default
    private int unreadCount = 0;

    /** User explicitly "marked as unread" (WhatsApp/Telegram) — keeps the chat
     *  flagged unread in the inbox even when the read marker is current, until it
     *  is next opened/read. */
    @Column(name = "marked_unread", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean markedUnread = false;

    /** Null = not muted. Mute suppresses push but NOT the unread count. */
    @Column(name = "muted_until")
    private LocalDateTime mutedUntil;

    @Column(name = "pinned", nullable = false)
    @Builder.Default
    private boolean pinned = false;

    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

    /**
     * "Delete conversation for me" high-water mark. When &gt; 0, the caller cleared
     * the thread at this Snowflake message id: everything up to and including it is
     * hidden on their side, the conversation drops out of both the inbox and the
     * archived list, and it re-surfaces (showing only the new messages) once the
     * peer sends a message with a larger id. 0 = never cleared. The
     * {@code columnDefinition} default lets {@code ddl-auto=update} add the column
     * to an existing table and back-fill old rows to 0.
     */
    @Column(name = "cleared_before_message_id", nullable = false,
            columnDefinition = "bigint not null default 0")
    @Builder.Default
    private long clearedBeforeMessageId = 0L;

    @Column(name = "joined_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();

    public boolean isActive()      { return status == MemberStatus.ACTIVE; }
    public boolean canRead()       { return status == MemberStatus.ACTIVE || status == MemberStatus.RESTRICTED; }
    public boolean isOwner()       { return role == MemberRole.OWNER; }
    public boolean isAdminOrOwner(){ return role == MemberRole.OWNER || role == MemberRole.ADMIN; }

    /** Build a fresh ACTIVE membership row for {@code userId} in {@code c}. */
    public static ConversationMember of(Conversation c, UUID userId, MemberRole role) {
        return ConversationMember.builder()
                .id(new ConversationMemberId(c.getId(), userId))
                .conversation(c)
                .role(role)
                .status(MemberStatus.ACTIVE)
                .joinedAt(LocalDateTime.now())
                .build();
    }
}
