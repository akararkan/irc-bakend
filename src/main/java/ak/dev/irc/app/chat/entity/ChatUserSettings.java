package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Per-user chat privacy settings (WhatsApp/Telegram style). Read receipts and
 * last-seen are <b>symmetric</b>: if you turn read receipts off, you also stop
 * seeing others' read receipts. Row is created lazily with all-on defaults.
 */
@Entity
@Table(name = "chat_user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatUserSettings extends BaseAuditEntity {

    /** The user id is the primary key (one settings row per user). */
    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    /** Send + see blue-tick read receipts. Off ⇒ your reads aren't reported and you
     *  don't see others' (symmetric). */
    @Column(name = "read_receipts_enabled", nullable = false)
    @Builder.Default
    private boolean readReceiptsEnabled = true;

    /** Expose your "last seen" / online status to others. Off ⇒ others can't see it
     *  and (symmetric) you can't see theirs. */
    @Column(name = "last_seen_visible", nullable = false)
    @Builder.Default
    private boolean lastSeenVisible = true;

    /** Emit typing indicators. Off ⇒ your typing isn't shown (and you won't be shown others'). */
    @Column(name = "typing_indicators_enabled", nullable = false)
    @Builder.Default
    private boolean typingIndicatorsEnabled = true;

    public static ChatUserSettings defaults(UUID userId) {
        return ChatUserSettings.builder().userId(userId).build();
    }
}
