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
 * <p>Inherits the parent story's per-row TTL (8h / 16h / 24h) — both rows
 * are written with the same {@code USING TTL} so the lookup never outlives
 * the story it points to.</p>
 *
 * <p>{@code expiresAt} is the absolute death time of the parent story.
 * Carried here so derived writes (story views, polls) can compute the
 * remaining TTL with one point-read instead of joining back to
 * {@code stories_by_author}. Null on rows created before this column was
 * added — callers must treat null as "default 24h lifetime".</p>
 *
 * <p>Schema migration required for existing keyspaces:
 * <pre>ALTER TABLE story_lookup ADD expires_at timestamp;</pre></p>
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
    @Column("expires_at") private Instant expiresAt;
}
