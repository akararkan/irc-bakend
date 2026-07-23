package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.JoinRequestStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A pending "let me in" request for an approval-gated channel — filed either by
 * an invite link with {@code requiresApproval} or by subscribing to a public
 * channel whose settings have {@code joinByRequest}. One row per
 * (conversation, user); a re-request after a rejection flips the same row back
 * to PENDING so the table can't accumulate duplicates.
 */
@Entity
@Table(
    name = "conversation_join_requests",
    uniqueConstraints = @UniqueConstraint(name = "uk_join_request_member",
                                          columnNames = {"conversation_id", "user_id"}),
    indexes = @Index(name = "idx_join_request_conversation", columnList = "conversation_id, status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationJoinRequest extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The invite link that routed here, or null for public join-by-request. */
    @Column(name = "invite_id")
    private UUID inviteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private JoinRequestStatus status = JoinRequestStatus.PENDING;

    /** The admin who approved/rejected, null while pending. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    public boolean isPending() { return status == JoinRequestStatus.PENDING; }
}
