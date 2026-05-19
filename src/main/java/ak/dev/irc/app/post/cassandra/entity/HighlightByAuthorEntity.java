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
 * Author's highlight covers — the row of pills above the profile feed.
 * No TTL: highlights persist forever, unlike the stories they archive.
 */
@Table("highlights_by_author")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HighlightByAuthorEntity {

    @PrimaryKeyColumn(name = "author_id", type = PrimaryKeyType.PARTITIONED)
    private UUID authorId;

    @PrimaryKeyColumn(name = "display_order", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Integer displayOrder;

    @PrimaryKeyColumn(name = "highlight_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID highlightId;

    @Column("title")      private String  title;
    @Column("cover_url")  private String  coverUrl;
    @Column("created_at") private Instant createdAt;
}
