package ak.dev.irc.app.common.tag.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * "All content (questions + research) tagged with #tag, newest first."
 *
 * <p>One partition per normalized (lowercased) tag. Mixed content types live in
 * the same partition so the tag feed shows everything; {@code content_type}
 * tells the client what each row is.</p>
 */
@Table("content_by_tag")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContentByTagEntity {

    @PrimaryKeyColumn(name = "tag", type = PrimaryKeyType.PARTITIONED)
    private String tag;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "content_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID contentId;

    @Column("content_type")  private String contentType;
    @Column("author_id")     private UUID   authorId;
    @Column("title_preview") private String titlePreview;
}
