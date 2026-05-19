package ak.dev.irc.app.activity.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Closes the gap left by {@code activity_by_user} (keyed by user_id +
 * created_at + activity_id) — given only an {@code activity_id} from the
 * delete-one URL, this is the point read that gets us the full primary
 * key + the activity_type partition for the type-table delete.
 */
@Table("activity_lookup")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActivityLookupEntity {

    @PrimaryKey
    @Column("activity_id")
    private UUID activityId;

    @Column("user_id")       private UUID    userId;
    @Column("activity_type") private String  activityType;
    @Column("created_at")    private Instant createdAt;
}
