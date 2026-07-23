package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.dto.request.MediaRefDto;
import ak.dev.irc.app.chat.enums.ScheduledMessageStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A message queued to send at a future time (Telegram "Schedule message"). A
 * scheduler polls for due rows and runs them through the normal send path, so
 * scheduled sends get the same permission checks, idempotency, fan-out and
 * realtime as a live send.
 */
@Entity
@Table(
    name = "scheduled_messages",
    indexes = {
        @Index(name = "idx_scheduled_due", columnList = "status, scheduled_at"),
        @Index(name = "idx_scheduled_conversation", columnList = "conversation_id"),
        @Index(name = "idx_scheduled_sender", columnList = "sender_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledMessage extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "type", nullable = false, length = 8)
    private String type;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media", columnDefinition = "jsonb")
    private List<MediaRefDto> media;

    @Column(name = "reply_to_id")
    private Long replyToId;

    /** Idempotency key reused when the scheduler actually sends. */
    @Column(name = "client_nonce", nullable = false, length = 64)
    private String clientNonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private ScheduledMessageStatus status = ScheduledMessageStatus.PENDING;

    /** The Snowflake id of the message actually sent (set when status → SENT). */
    @Column(name = "sent_message_id")
    private Long sentMessageId;
}
