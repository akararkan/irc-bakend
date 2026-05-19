package ak.dev.irc.app.post.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch document for full-text search across posts.
 *
 * Cassandra owns the canonical post data; this index is an eventually
 * consistent secondary copy. On post create we index here async; on update
 * we re-index; on delete we delete here too.
 *
 * Index name: irc-posts (one shard per node is plenty for early scale).
 */
@Document(indexName = "irc-posts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostSearchDocument {

    @Id
    private String id;          // string form of the post UUID — ES native id

    @Field(type = FieldType.Keyword) private String  authorId;

    /**
     * Primary searchable field. Standard analyser handles tokenisation + lowercasing;
     * the "ngram" sub-field would enable prefix-as-you-type if added later.
     */
    @Field(type = FieldType.Text)    private String  textContent;

    @Field(type = FieldType.Text)    private String  authorName;
    @Field(type = FieldType.Text)    private String  authorUsername;

    @Field(type = FieldType.Keyword) private String  postType;
    @Field(type = FieldType.Keyword) private String  visibility;
    @Field(type = FieldType.Keyword) private String  status;

    @Field(type = FieldType.Keyword) private List<String> hashtags;
    @Field(type = FieldType.Keyword) private List<String> mentionedUserIds;

    @Field(type = FieldType.Keyword) private String  locationName;
    @Field(type = FieldType.Double)  private Double  locationLat;
    @Field(type = FieldType.Double)  private Double  locationLng;

    /** Score fields for ranking. Pulled from post_counters and refreshed periodically. */
    @Field(type = FieldType.Long)    private Long    reactionCount;
    @Field(type = FieldType.Long)    private Long    commentCount;
    @Field(type = FieldType.Long)    private Long    viewCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID postId) { return postId.toString(); }
}
