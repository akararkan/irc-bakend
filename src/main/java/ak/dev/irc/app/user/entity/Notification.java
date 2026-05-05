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
        @Index(name = "idx_notif_user",      columnList = "user_id"),
        @Index(name = "idx_notif_is_read",   columnList = "user_id, is_read"),
        @Index(name = "idx_notif_actor",     columnList = "actor_id"),
        // Hot-path index for aggregation lookups: same recipient, same group,
        // unread. Pushes the dedupe query down to a single index seek.
        @Index(name = "idx_notif_group",     columnList = "user_id, group_key, is_read")
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

    // ── Aggregation ────────────────────────────────────────────────────
    /**
     * Coalescing key — same {@code (userId, groupKey)} unread rows are merged
     * into one. Format is {@code TYPE:resourceId} (e.g. {@code POST_REACTED:abc}).
     * Null disables aggregation for one-off notifications (system / unblock /
     * accepted / etc.).
     */
    @Column(name = "group_key", length = 120)
    private String groupKey;

    /** Number of underlying events this row represents. Defaults to 1. */
    @Column(name = "aggregate_count", nullable = false)
    @Builder.Default
    private Long aggregateCount = 1L;

    /** Most recent actor in the aggregated stream — surfaced as the avatar. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_actor_id",
                foreignKey = @ForeignKey(name = "fk_notification_last_actor"))
    private User lastActor;

    public void markAsRead() {
        this.isRead  = true;
        this.readAt  = LocalDateTime.now();
    }

    /**
     * Coalesce another event into this notification: bump the count, update
     * {@link #lastActor}, refresh {@code createdAt} so the row floats to the
     * top of the inbox.
     */
    public void coalesce(User newActor, String newBody) {
        this.aggregateCount = (this.aggregateCount == null ? 1L : this.aggregateCount) + 1L;
        if (newActor != null) {
            this.lastActor = newActor;
            // The latest actor becomes the primary avatar so the inbox row
            // shows whoever triggered the most recent event.
            this.actor = newActor;
        }
        if (newBody != null) {
            this.body = newBody;
        }
        // Bump createdAt so the inbox sort order brings the coalesced row up.
        // Updates BaseAuditEntity#updatedAt automatically; we touch createdAt
        // directly because the inbox is ordered by it.
        this.setCreatedAt(LocalDateTime.now());
        // A new event resets the read state — user should re-see it.
        this.isRead = false;
        this.readAt = null;
    }
}
