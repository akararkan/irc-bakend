package ak.dev.irc.app.settings.notification.entity;

import ak.dev.irc.app.settings.notification.enums.NotificationChannel;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One cell of the notification preference matrix (spec §8):
 * {@code (user, eventType, channel) → enabled}. An <em>absent</em> row means
 * "platform default" (resolved in {@code NotificationDefaults}), never "off".
 *
 * <p>{@code eventType} is a free-form string key (typically a
 * {@code NotificationType} name, but also synthetic keys like
 * {@code SECURITY_ALERT}) so the matrix evolves without touching the exhaustive
 * notification-type enum or its email-template switch.</p>
 */
@Entity
@Table(name = "user_notification_prefs",
       uniqueConstraints = @UniqueConstraint(name = "uq_notif_pref",
               columnNames = {"user_id", "event_type", "channel"}),
       indexes = @Index(name = "idx_notif_pref_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotificationPref {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
