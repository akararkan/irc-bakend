package ak.dev.irc.app.post.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.UUID;

/**
 * Elasticsearch document for sound-library search (the reels/stories sound
 * picker and {@code /api/v1/search?types=SOUND}).
 *
 * <p>Cassandra ({@code sounds_by_id}) is canonical. Only APPROVED sounds are
 * meant to surface: the status is stored here and filtered at query time
 * (PENDING_REVIEW / REJECTED / ARCHIVED are in the global dead-status list),
 * so an approval flip is one re-index away from being searchable.</p>
 */
@Document(indexName = "irc-sounds", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SoundSearchDocument {

    @Id
    private String id;          // string form of the sound UUID

    @Field(type = FieldType.Text)    private String title;
    @Field(type = FieldType.Text)    private String artistName;

    @Field(type = FieldType.Keyword) private String category;
    /** APPROVED / PENDING_REVIEW / REJECTED / ARCHIVED — non-APPROVED never surfaces. */
    @Field(type = FieldType.Keyword) private String status;

    /** How many posts/reels use this sound — popularity ranking signal. */
    @Field(type = FieldType.Long)    private Long   useCount;

    /** Uploader — powers the admin review-queue + reputation views. */
    @Field(type = FieldType.Keyword) private String uploaderId;

    /** First-party "official ✓" flag. */
    @Field(type = FieldType.Boolean) private Boolean official;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID soundId) { return soundId.toString(); }
}
