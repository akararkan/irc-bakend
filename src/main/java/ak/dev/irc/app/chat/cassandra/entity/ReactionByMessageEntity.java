package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per user per message — a user's current reaction to a message.
 * Partition = {@code message_id}, clustering = {@code user_id}, so add/remove is
 * a single upsert/delete and "who reacted to this message" is a one-partition
 * scan. Aggregate counts for display are cached in Redis
 * ({@code chat:reactions:{messageId}} hash of emoji→count) and recomputed lazily.
 */
@Table("reactions_by_message")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReactionByMessageEntity {

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.PARTITIONED)
    private Long messageId;

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.CLUSTERED, ordinal = 1)
    private UUID userId;

    @Column("emoji")      private String emoji;
    @Column("created_at") private Instant createdAt;
}
