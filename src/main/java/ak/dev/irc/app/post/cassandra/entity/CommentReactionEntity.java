package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("comment_reactions_by_comment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommentReactionEntity {

    @PrimaryKeyColumn(name = "comment_id", type = PrimaryKeyType.PARTITIONED)
    private UUID commentId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("created_at") private Instant createdAt;
}
