package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message pinned in a conversation (Telegram-style). The message body itself
 * lives in Cassandra; this small Postgres row records <i>which</i> message ids
 * are pinned so "show pinned messages" is a cheap relational lookup. Multiple
 * messages may be pinned per conversation (newest pin first).
 */
@Entity
@Table(
    name = "conversation_pins",
    uniqueConstraints = @UniqueConstraint(name = "uk_pin_conversation_message",
                                          columnNames = {"conversation_id", "message_id"}),
    indexes = @Index(name = "idx_pin_conversation", columnList = "conversation_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationPin extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "message_id", nullable = false)
    private long messageId;

    @Column(name = "pinned_by", nullable = false)
    private UUID pinnedBy;

    @Column(name = "pinned_at", nullable = false)
    @Builder.Default
    private LocalDateTime pinnedAt = LocalDateTime.now();
}
