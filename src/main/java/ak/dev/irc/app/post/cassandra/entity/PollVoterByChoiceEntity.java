package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Author-facing voter list: "who voted A?", "who voted B?". Composite
 * partition key (poll_id, choice) so each side is its own partition, and
 * voted_at DESC clustering so we read newest-first.
 */
@Table("poll_voters_by_choice")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollVoterByChoiceEntity {

    @PrimaryKeyColumn(name = "poll_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private UUID pollId;

    @PrimaryKeyColumn(name = "choice", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private String choice;

    @PrimaryKeyColumn(name = "voted_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant votedAt;

    @PrimaryKeyColumn(name = "voter_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private UUID voterId;
}
