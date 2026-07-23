package ak.dev.irc.app.chat.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * Elasticsearch document for full-text message search. Cassandra owns the
 * canonical message; this index is an eventually-consistent secondary copy
 * written async on send/edit and removed on delete.
 *
 * <p>{@code createIndex=false} — the index is created lazily by the first
 * save/search so a missing ES instance never crashes startup (same policy as the
 * other search indices).</p>
 */
@Document(indexName = "irc-chat-messages", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessageDocument {

    /** String form of the Snowflake message id — the ES native id. */
    @Id
    private String id;

    @Field(type = FieldType.Long)    private Long   messageId;
    @Field(type = FieldType.Keyword) private String conversationId;
    @Field(type = FieldType.Keyword) private String senderId;

    /** The primary searchable field. */
    @Field(type = FieldType.Text)    private String body;

    @Field(type = FieldType.Keyword) private String type;

    /** Lowercased #hashtags for exact-term tag search. */
    @Field(type = FieldType.Keyword) private java.util.List<String> tags;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(long messageId) { return Long.toString(messageId); }
}
