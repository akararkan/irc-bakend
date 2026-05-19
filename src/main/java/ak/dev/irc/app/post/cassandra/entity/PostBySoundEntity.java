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
 * "All posts using sound X" — the TikTok pattern. Partition per sound;
 * clustered newest first so the discover page is a partition slice.
 */
@Table("posts_by_sound")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostBySoundEntity {

    @PrimaryKeyColumn(name = "sound_id", type = PrimaryKeyType.PARTITIONED)
    private UUID soundId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "post_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID postId;

    @Column("author_id") private UUID authorId;
}
