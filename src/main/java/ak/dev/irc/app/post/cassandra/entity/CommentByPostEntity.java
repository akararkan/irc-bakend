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
 * Top-level comments for a post. Clustered ASCending by created_at so the
 * conversation reads chronologically when scanning forward.
 */
@Table("comments_by_post")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommentByPostEntity {

    @PrimaryKeyColumn(name = "post_id", type = PrimaryKeyType.PARTITIONED)
    private UUID postId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "comment_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID commentId;

    @Column("author_id")    private UUID   authorId;
    @Column("text_content") private String textContent;
    @Column("media_url")    private String mediaUrl;
    @Column("media_type")   private String mediaType;
    @Column("is_deleted")   private Boolean deleted;
    @Column("is_edited")    private Boolean edited;
}
