package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Messenger-style "Message Request" — the inbox for first contact from someone
 * you are not connected to. Exactly one pending row per (recipient, requester)
 * is enforced by the unique constraint, which is the anti-spam backbone.
 */
@Entity
@Table(
    name = "message_requests",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_message_request_pair",
        columnNames = {"recipient_id", "requester_id"}
    ),
    indexes = @Index(name = "idx_request_inbox", columnList = "recipient_id, status, created_at DESC")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageRequest extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** Who sent the first message. */
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    /** Who must accept. */
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private MessageRequestStatus status = MessageRequestStatus.PENDING;

    @Column(name = "first_message_id", nullable = false)
    private long firstMessageId;

    /**
     * Count of messages the stranger has sent into this un-accepted thread — used
     * to cap pre-acceptance spam (further sends blocked once the cap is hit).
     */
    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private int messageCount = 1;
}
