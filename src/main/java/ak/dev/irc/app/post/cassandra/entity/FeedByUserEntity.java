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
 * Home feed — fanout-on-write. One row per viewer per published item
 * they should see. Reading the feed for user U = scanning a single
 * partition (PK = user_id). TTL = 30 days at the table level; older
 * entries re-hydrate on cold reads.
 *
 * <p><b>Multi-entity</b>: as of the mixed-feed feature, this table
 * holds POSTs, RESEARCH and QUESTIONs together — discriminated by
 * {@code entity_type}. The {@code post_id} column is a misnomer for
 * non-POST entries (it carries researchId / questionId); kept under
 * the original name to avoid an expensive Cassandra column rename.
 * Older rows (pre-feature) have {@code entity_type=null}; the read
 * path treats {@code null} as POST.</p>
 *
 * <p>Cassandra migration (run once per keyspace):
 * <pre>ALTER TABLE feed_by_user ADD entity_type text;</pre></p>
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
    /**
     * One of {@code POST | RESEARCH | QUESTION} — null on legacy rows,
     * which the read layer treats as POST for back-compat.
     */
    @Column("entity_type")  private String entityType;
}
