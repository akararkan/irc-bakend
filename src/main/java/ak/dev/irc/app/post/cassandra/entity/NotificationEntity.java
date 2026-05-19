package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-user notification inbox. Partition per user, clustered newest first.
 * 90-day TTL is set at the table level — older notifications drop off.
 *
 * Aggregation: when several events of the same kind hit a user in quick
 * succession (e.g. multiple likes on one post), the dispatcher writes ONE row
 * and updates {@code aggregateCount} + {@code lastActorId} so the client can
 * render "Alice and 4 others liked your post" instead of 5 separate rows.
 * The aggregation window is 60 min, enforced via {@code notif_active_group_by_user}.
 */
@Table("notifications_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "notification_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID notificationId;

    @Column("type")            private String  type;
    @Column("title")           private String  title;
    @Column("body")            private String  body;
    @Column("actor_id")        private UUID    actorId;
    @Column("last_actor_id")   private UUID    lastActorId;
    @Column("aggregate_count") private Long    aggregateCount;
    @Column("resource_type")   private String  resourceType;
    @Column("resource_id")     private UUID    resourceId;
    @Column("group_key")       private String  groupKey;
    @Column("is_read")         private Boolean read;
}
