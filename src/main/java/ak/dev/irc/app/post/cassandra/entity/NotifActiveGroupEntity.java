package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Pointer from (user_id, group_key) → the currently-live notification row we
 * should aggregate into. Inserted on first delivery of a group; TTL'd 60 min
 * so the next same-group event after the window starts a fresh row.
 */
@Table("notif_active_group_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotifActiveGroupEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "group_key", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private String groupKey;

    @Column("notification_id") private UUID    notificationId;
    @Column("created_at")      private Instant createdAt;
}
