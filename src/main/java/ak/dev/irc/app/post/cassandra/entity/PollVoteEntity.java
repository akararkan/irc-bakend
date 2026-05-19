package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * "Has user U voted on poll P? Which side?" — point read by (poll_id, voter_id).
 * Used to enforce one-vote-per-user and to show the user their own pick.
 * 24h TTL inherited from the story.
 */
@Table("poll_votes_by_poll_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollVoteEntity {

    @PrimaryKeyColumn(name = "poll_id", type = PrimaryKeyType.PARTITIONED)
    private UUID pollId;

    @PrimaryKeyColumn(name = "voter_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID voterId;

    @Column("choice")   private String  choice;     // 'A' or 'B'
    @Column("voted_at") private Instant votedAt;
}
