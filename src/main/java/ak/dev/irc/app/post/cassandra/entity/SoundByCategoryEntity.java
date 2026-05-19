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
 * Sound library browser — newest in each category first.
 * Only APPROVED sounds are written here; PENDING_REVIEW lives only in
 * sounds_by_id until a moderator approves it.
 */
@Table("sounds_by_category")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SoundByCategoryEntity {

    @PrimaryKeyColumn(name = "category", type = PrimaryKeyType.PARTITIONED)
    private String category;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "sound_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID soundId;

    @Column("title")            private String  title;
    @Column("artist_name")      private String  artistName;
    @Column("audio_url")        private String  audioUrl;
    @Column("cover_art_url")    private String  coverArtUrl;
    @Column("duration_seconds") private Integer durationSeconds;
}
