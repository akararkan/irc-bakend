package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** Canonical sound record. Point-read by id. */
@Table("sounds_by_id")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SoundEntity {

    @PrimaryKey
    private UUID id;

    @Column("title")            private String  title;
    @Column("artist_name")      private String  artistName;
    @Column("audio_url")        private String  audioUrl;
    @Column("cover_art_url")    private String  coverArtUrl;
    @Column("duration_seconds") private Integer durationSeconds;
    @Column("category")         private String  category;
    @Column("status")           private String  status;
    @Column("uploader_id")      private UUID    uploaderId;
    @Column("created_at")       private Instant createdAt;
    @Column("updated_at")       private Instant updatedAt;
}
