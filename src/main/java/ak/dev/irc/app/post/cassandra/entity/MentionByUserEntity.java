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
 * Per-user mention inbox — newest first. One row per direct {@code @mention}
 * of {@code mentionedUserId}, across every mention-bearing surface.
 *
 * <p>Historically posts-only, hence the {@code post_id} clustering column —
 * for non-post sources it now holds the SOURCE id (comment / research /
 * question / answer id) and {@code source_type} says which. Legacy rows have
 * {@code source_type = null}, which readers must treat as {@code POST}.</p>
 */
@Table("mentions_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MentionByUserEntity {

    @PrimaryKeyColumn(name = "mentioned_user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID mentionedUserId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "post_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID postId;

    @Column("author_id")    private UUID   authorId;
    @Column("text_preview") private String textPreview;

    /** {@code MentionSource} name (POST_COMMENT, RESEARCH, …); null = POST (legacy rows). */
    @Column("source_type")      private String sourceType;

    /** The navigable parent for nested sources (comment → its post, answer → its question). */
    @Column("source_parent_id") private UUID   sourceParentId;
}
