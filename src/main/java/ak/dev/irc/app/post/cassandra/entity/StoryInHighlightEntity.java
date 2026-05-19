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
 * A story copied into a highlight archive. Story content is DENORMALIZED here
 * because the original row in stories_by_author will vanish after 24h —
 * the highlight needs to be self-contained.
 */
@Table("stories_in_highlight")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryInHighlightEntity {

    @PrimaryKeyColumn(name = "highlight_id", type = PrimaryKeyType.PARTITIONED)
    private UUID highlightId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "story_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID storyId;

    @Column("author_id")     private UUID    authorId;
    @Column("story_type")    private String  storyType;
    @Column("media_url")     private String  mediaUrl;
    @Column("thumbnail_url") private String  thumbnailUrl;
    @Column("text_content")  private String  textContent;
}
