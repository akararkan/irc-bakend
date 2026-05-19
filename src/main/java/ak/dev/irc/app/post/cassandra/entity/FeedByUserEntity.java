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
 * Home feed — fanout-on-write. One row per viewer per post they should see.
 * Reading the feed for user U = scanning a single partition (PK = user_id).
 * TTL = 30 days at the table level; older entries re-hydrate on cold reads.
 */
@Table("feed_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FeedByUserEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "post_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID postId;

    @Column("author_id")    private UUID   authorId;
    @Column("post_type")    private String postType;
    @Column("text_preview") private String textPreview;
    @Column("media_url")    private String mediaUrl;
}
