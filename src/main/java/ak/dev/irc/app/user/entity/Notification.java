package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "notifications",
    indexes = {
        @Index(name = "idx_notif_user",    columnList = "user_id"),
        @Index(name = "idx_notif_is_read", columnList = "user_id, is_read"),
        @Index(name = "idx_notif_actor",   columnList = "actor_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Who receives this notification */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_notification_user"))
    private User user;

    /** Who triggered this notification — null for system notifications */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id",
                foreignKey = @ForeignKey(name = "fk_notification_actor"))
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Optional reference to the related resource (publication, comment, etc.) */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "resource_type", length = 60)
    private String resourceType;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public void markAsRead() {
        this.isRead  = true;
        this.readAt  = LocalDateTime.now();
    }
}
