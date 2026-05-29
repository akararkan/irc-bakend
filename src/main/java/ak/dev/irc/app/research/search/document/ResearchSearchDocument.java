package ak.dev.irc.app.research.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// createIndex=false → don't ping Elasticsearch from the repository constructor
// at boot. Index is created lazily by the first save() / search() call.
@Document(indexName = "irc-research", createIndex = false)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchSearchDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text)    private String title;
    @Field(type = FieldType.Text)    private String abstractText;
    @Field(type = FieldType.Text)    private String description;
    @Field(type = FieldType.Text)    private String keywords;

    @Field(type = FieldType.Keyword) private List<String> tags;

    @Field(type = FieldType.Keyword) private String researcherId;
    @Field(type = FieldType.Text)    private String researcherName;
    @Field(type = FieldType.Text)    private String researcherUsername;

    @Field(type = FieldType.Keyword) private String status;
    @Field(type = FieldType.Keyword) private String ircId;
    @Field(type = FieldType.Keyword) private String slug;

    @Field(type = FieldType.Long) private Long viewCount;
    @Field(type = FieldType.Long) private Long citationCount;
    @Field(type = FieldType.Long) private Long reactionCount;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant publishedAt;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public static String idOf(UUID researchId) { return researchId.toString(); }
}
