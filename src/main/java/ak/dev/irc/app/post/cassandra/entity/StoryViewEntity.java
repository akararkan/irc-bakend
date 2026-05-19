package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("story_views_by_story")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryViewEntity {

    @PrimaryKeyColumn(name = "story_id", type = PrimaryKeyType.PARTITIONED)
    private UUID storyId;

    @PrimaryKeyColumn(name = "viewed_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant viewedAt;

    @PrimaryKeyColumn(name = "viewer_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID viewerId;
}
