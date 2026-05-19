package ak.dev.irc.app.qna.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** "Has user U saved question Q?" point lookup. */
@Table("question_saves_lookup")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionSaveLookupEntity {

    @PrimaryKeyColumn(name = "question_id", type = PrimaryKeyType.PARTITIONED)
    private UUID questionId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("created_at") private Instant createdAt;
}
