package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** Answers: "Did user U react to post P?" — point read by (post_id, user_id). */
@Table("reactions_by_post")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReactionByPostEntity {

    @PrimaryKeyColumn(name = "post_id", type = PrimaryKeyType.PARTITIONED)
    private UUID postId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("created_at") private Instant createdAt;
}
