package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Image grid / carousel media per post. Clustering by sort_order ASC so the
 * read order matches what the frontend renders (page 1 of the carousel first).
 */
@Table("media_by_post")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaByPostEntity {

    @PrimaryKeyColumn(name = "post_id", type = PrimaryKeyType.PARTITIONED)
    private UUID postId;

    @PrimaryKeyColumn(name = "sort_order", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Integer sortOrder;

    @PrimaryKeyColumn(name = "media_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID mediaId;

    @Column("media_type")       private String  mediaType;
    @Column("url")              private String  url;
    @Column("thumbnail_url")    private String  thumbnailUrl;
    @Column("s3_key")           private String  s3Key;
    @Column("duration_seconds") private Integer durationSeconds;
    @Column("file_size_bytes")  private Long    fileSizeBytes;
    @Column("mime_type")        private String  mimeType;
    @Column("alt_text")         private String  altText;
}
