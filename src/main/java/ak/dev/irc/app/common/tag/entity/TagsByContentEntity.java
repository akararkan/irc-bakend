package ak.dev.irc.app.common.tag.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Reverse index: "which tags does this content carry?"
 *
 * <p>Needed so an edit or delete can clean up {@code content_by_tag} and
 * decrement {@code tag_counters} <em>without</em> re-parsing the (possibly
 * changed) text. {@code created_at} is duplicated here so the matching
 * {@code content_by_tag} row — whose clustering key includes {@code created_at}
 * — can be deleted precisely.</p>
 */
@Table("tags_by_content")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TagsByContentEntity {

    @PrimaryKeyColumn(name = "content_id", type = PrimaryKeyType.PARTITIONED)
    private UUID contentId;

    @PrimaryKeyColumn(name = "tag", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private String tag;

    @Column("content_type") private String  contentType;
    @Column("created_at")   private Instant createdAt;
}
