package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.CassandraType;
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

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("reaction_count") private Long reactionCount;
    @CassandraType(type = CassandraType.Name.COUNTER) @Column("comment_count")  private Long commentCount;
    @CassandraType(type = CassandraType.Name.COUNTER) @Column("share_count")    private Long shareCount;
    @CassandraType(type = CassandraType.Name.COUNTER) @Column("view_count")     private Long viewCount;
    @CassandraType(type = CassandraType.Name.COUNTER) @Column("save_count")     private Long saveCount;
}
