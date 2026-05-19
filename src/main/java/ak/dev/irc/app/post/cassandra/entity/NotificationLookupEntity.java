package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Point-lookup by notification_id. Needed because notifications_by_user is
 * keyed by (user_id, created_at, notification_id) — "mark this notification
 * read" gives us only the notification_id from the URL.
 */
@Table("notification_lookup")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationLookupEntity {

    @PrimaryKey
    @Column("notification_id")
    private UUID notificationId;

    @Column("user_id")    private UUID    userId;
    @Column("created_at") private Instant createdAt;
}
