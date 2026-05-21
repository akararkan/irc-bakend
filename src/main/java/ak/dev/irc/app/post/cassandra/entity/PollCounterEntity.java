package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/** Live tally per poll. Counter writes go through CounterService. */
@Table("poll_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollCounterEntity {

    @PrimaryKey
    @Column("poll_id")
    private UUID pollId;

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("vote_a") private Long voteA;
    @CassandraType(type = CassandraType.Name.COUNTER) @Column("vote_b") private Long voteB;
}
