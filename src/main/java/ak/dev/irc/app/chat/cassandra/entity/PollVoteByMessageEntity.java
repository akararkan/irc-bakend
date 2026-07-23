package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One row per (poll message, voter) — the source of truth for poll votes,
 * mirroring {@link ReactionByMessageEntity}. Aggregate option counts live in a
 * delta-maintained Redis hash and are lazily rebuilt from this table when cold.
 */
@Table("poll_votes_by_message")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollVoteByMessageEntity {

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.PARTITIONED)
    private Long messageId;

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    /** Chosen option indexes (singleton unless the poll allows multiple answers). */
    @Column("options")
    private Set<Integer> options;

    @Column("created_at")
    private Instant createdAt;
}
