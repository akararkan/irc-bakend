package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message a user has starred / bookmarked (WhatsApp "Starred", Telegram
 * "Saved"). Per-user; the same message can be starred by many users. Relational
 * so "my starred messages" is a cheap indexed Postgres query.
 */
@Entity
@Table(
    name = "message_stars",
    uniqueConstraints = @UniqueConstraint(name = "uk_star_user_message",
                                          columnNames = {"user_id", "message_id"}),
    indexes = @Index(name = "idx_star_user", columnList = "user_id, starred_at DESC")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageStar extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "message_id", nullable = false)
    private long messageId;

    @Column(name = "starred_at", nullable = false)
    @Builder.Default
    private LocalDateTime starredAt = LocalDateTime.now();
}
