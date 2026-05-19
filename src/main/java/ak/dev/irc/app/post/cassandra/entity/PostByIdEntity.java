package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Canonical post record. One row per post_id. Look up by id only.
 * Sibling tables (posts_by_author, feed_by_user, etc.) hold denormalized copies
 * keyed for their respective query patterns.
 */
@Table("posts_by_id")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostByIdEntity {

    @PrimaryKey
    private UUID id;

    @Column("author_id")     private UUID    authorId;
    @Column("post_type")     private String  postType;
    @Column("status")        private String  status;
    @Column("visibility")    private String  visibility;
    @Column("text_content")  private String  textContent;
    @Column("audio_track_url") private String audioTrackUrl;
    @Column("audio_track_name") private String audioTrackName;
    @Column("location_name") private String  locationName;
    @Column("location_lat")  private Double  locationLat;
    @Column("location_lng")  private Double  locationLng;
    @Column("shared_post_id") private UUID   sharedPostId;
    @Column("share_link")    private String  shareLink;
    @Column("media_urls")    private List<String> mediaUrls;
    @Column("media_types")   private List<String> mediaTypes;
    @Column("created_at")    private Instant createdAt;
    @Column("updated_at")    private Instant updatedAt;
}
