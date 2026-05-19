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
 * Precomputed friend suggestions for a user.
 * "score" = how many people they both follow (mutual count) — clustered DESC
 * so the top suggestions appear first on a single partition scan.
 */
@Table("friend_suggestions_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FriendSuggestionEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "score", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Integer score;

    @PrimaryKeyColumn(name = "candidate_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID candidateId;

    @Column("reason")      private String  reason;
    @Column("computed_at") private Instant computedAt;
}
