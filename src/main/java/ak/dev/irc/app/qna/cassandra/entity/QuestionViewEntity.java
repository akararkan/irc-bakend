package ak.dev.irc.app.qna.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** Unique-viewer set for a question. Mirrors {@code views_by_post}. */
@Table("question_views_by_question")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionViewEntity {

    @PrimaryKeyColumn(name = "question_id", type = PrimaryKeyType.PARTITIONED)
    private UUID questionId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("first_viewed_at") private Instant firstViewedAt;
}
