package ak.dev.irc.app.qna.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.UUID;

/**
 * Elasticsearch document for answer-level Q&amp;A search.
 *
 * <p>Questions and answers live in <em>separate</em> indices ({@code irc-qna}
 * / {@code irc-answers}) rather than mixed doc-types in one index, so the
 * existing question queries need no extra type filter and each side keeps its
 * own scoring signals. An answer hit carries {@code questionId} so the client
 * can deep-link into the question page (surfaced as {@code parentId} on the
 * global search hit).</p>
 *
 * <p>Reanswers (depth-1 replies) are indexed too — they are content. The
 * author-accepted answer gets a query-time weight boost in
 * {@code GlobalSearchService} via the {@code accepted} flag.</p>
 */
@Document(indexName = "irc-answers", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AnswerSearchDocument {

    @Id
    private String id;          // string form of the answer UUID

    @Field(type = FieldType.Keyword) private String questionId;
    /** Parent question's title — secondary relevance signal + hit context. */
    @Field(type = FieldType.Text)    private String questionTitle;

    /** Primary searchable field. */
    @Field(type = FieldType.Text)    private String body;

    @Field(type = FieldType.Keyword) private String authorId;
    @Field(type = FieldType.Text)    private String authorName;
    @Field(type = FieldType.Text)    private String authorUsername;

    /** Author-accepted answer — boosted at query time. */
    @Field(type = FieldType.Boolean) private Boolean accepted;
    /** Always {@code ACTIVE} — soft-deleted answers are removed from the index. */
    @Field(type = FieldType.Keyword) private String  status;

    @Field(type = FieldType.Long)    private Long reactionCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID answerId) { return answerId.toString(); }
}
