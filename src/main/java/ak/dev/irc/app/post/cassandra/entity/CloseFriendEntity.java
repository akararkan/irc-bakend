package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per (owner, friend) pair. The owner's "trusted" inner-circle
 * viewer list for CLOSE_FRIENDS-scoped stories.
 *
 * Partition by owner_id so listing my close friends is a single partition
 * scan; cluster by friend_id so "is U on my list?" is a point read.
 */
@Table("close_friends_by_owner")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CloseFriendEntity {

    @PrimaryKeyColumn(name = "owner_id", type = PrimaryKeyType.PARTITIONED)
    private UUID ownerId;

    @PrimaryKeyColumn(name = "friend_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID friendId;

    @Column("added_at") private Instant addedAt;
}
