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

    @Column(name = "unread_count", nullable = false)
    @Builder.Default
    private int unreadCount = 0;

    /** Null = not muted. Mute suppresses push but NOT the unread count. */
    @Column(name = "muted_until")
    private LocalDateTime mutedUntil;

    @Column(name = "pinned", nullable = false)
    @Builder.Default
    private boolean pinned = false;

    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

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
