package ak.dev.irc.app.settings.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A device push token (spec §8 token hygiene). Stored per session; deleted on
 * logout and on a provider {@code UNREGISTERED} response so invalid tokens don't
 * slowly poison delivery metrics.
 */
@Entity
@Table(name = "push_tokens",
       uniqueConstraints = @UniqueConstraint(name = "uq_push_token", columnNames = {"token"}),
       indexes = @Index(name = "idx_push_token_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Owning session id (matches {@code RefreshToken.sid}) so logout can purge. */
    @Column(name = "sid")
    private UUID sid;

    /** FCM | APNS. */
    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    /** IOS | ANDROID | WEB. */
    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @PrePersist
    void onCreate() {
        if (lastSeenAt == null) lastSeenAt = LocalDateTime.now();
    }
}
