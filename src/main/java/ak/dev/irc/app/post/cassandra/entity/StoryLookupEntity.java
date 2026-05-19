package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Point-read by story_id alone — closes the gap left by stories_by_author
 * which is keyed on (author_id, created_at, story_id). Used by delete /
 * visibility-check / view-record paths that only have the story_id from
 * the URL.
 *
 * Inherits the same 24h TTL as the underlying story.
 */
@Table("story_lookup")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryLookupEntity {

    @PrimaryKey
    @Column("story_id")
    private UUID storyId;

    @Column("author_id")  private UUID    authorId;
    @Column("created_at") private Instant createdAt;
    @Column("visibility") private String  visibility;
}
