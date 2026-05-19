package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Counter columns. CANNOT be written via standard save() — only via
 * UPDATE … SET col = col + N statements through CassandraOperations.
 * The service layer wraps these calls.
 */
@Table("post_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostCounterEntity {

    @PrimaryKey
    @Column("post_id")
    private UUID postId;

    @Column("reaction_count") private Long reactionCount;
    @Column("comment_count")  private Long commentCount;
    @Column("share_count")    private Long shareCount;
    @Column("view_count")     private Long viewCount;
    @Column("save_count")     private Long saveCount;
}
