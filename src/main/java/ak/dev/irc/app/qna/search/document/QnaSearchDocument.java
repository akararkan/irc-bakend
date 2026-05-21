package ak.dev.irc.app.qna.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.UUID;

@Document(indexName = "irc-qna", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QnaSearchDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text)    private String title;
    @Field(type = FieldType.Text)    private String body;

    @Field(type = FieldType.Keyword) private String authorId;
    @Field(type = FieldType.Text)    private String authorName;
    @Field(type = FieldType.Text)    private String authorUsername;

    @Field(type = FieldType.Keyword) private String status;

    @Field(type = FieldType.Long) private Long answerCount;
    @Field(type = FieldType.Long) private Long viewCount;
    @Field(type = FieldType.Long) private Long saveCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID questionId) { return questionId.toString(); }
}
