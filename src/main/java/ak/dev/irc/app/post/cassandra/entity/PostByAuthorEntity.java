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
 * Profile feed — newest-first list of a user's own posts.
 * Partition key: author_id  (one partition per user).
 * Clustering: created_at DESC, post_id (paginates by created_at).
 */
@Table("posts_by_author")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostByAuthorEntity {

    @PrimaryKeyColumn(name = "author_id", type = PrimaryKeyType.PARTITIONED)
    private UUID authorId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "post_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID postId;

    @Column("post_type")    private String postType;
    @Column("visibility")   private String visibility;
    @Column("text_preview") private String textPreview;
    @Column("media_url")    private String mediaUrl;
}
