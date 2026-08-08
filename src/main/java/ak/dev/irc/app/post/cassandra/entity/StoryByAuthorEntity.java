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
 * Author's active stories. 24h TTL is set at the table level — Cassandra
 * tombstones the rows automatically, no expiry job needed.
 */
@Table("stories_by_author")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryByAuthorEntity {

    @PrimaryKeyColumn(name = "author_id", type = PrimaryKeyType.PARTITIONED)
    private UUID authorId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "story_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID storyId;

    @Column("story_type")    private String  storyType;
    @Column("visibility")    private String  visibility;
    @Column("media_url")     private String  mediaUrl;
    @Column("thumbnail_url") private String  thumbnailUrl;
    @Column("text_content")  private String  textContent;
    @Column("expires_at")    private Instant expiresAt;

    /**
     * Automated-moderation state. Stories are ephemeral, so the hold ceiling is
     * deliberately short and the fallback is fail-open-shadow — a 24h story that
     * spends its whole life in a queue has effectively been deleted
     * (MODERATION_ROADMAP.md §5.3, §5.6). NULL reads as approved.
     */
    @Column("moderation_status") private String moderationStatus;
}
