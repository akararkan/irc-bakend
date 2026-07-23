package ak.dev.irc.app.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message a user has "deleted for me" (WhatsApp "Delete for me"). Per-user
 * hide of a single message — the message stays in the timeline for everyone
 * else. The read path filters these ids out for the owning user.
 *
 * <p>Composite key (user_id, message_id). Kept deliberately lean (no audit
 * columns) — it is a high-cardinality tombstone set, not a domain entity.</p>
 */
@Entity
@Table(name = "hidden_messages",
       indexes = @Index(name = "idx_hidden_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(HiddenMessage.Key.class)
public class HiddenMessage {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "message_id", nullable = false)
    private long messageId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "hidden_at", nullable = false)
    @Builder.Default
    private LocalDateTime hiddenAt = LocalDateTime.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private long messageId;
    }
}
