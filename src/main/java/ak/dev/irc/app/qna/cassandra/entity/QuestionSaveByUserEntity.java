package ak.dev.irc.app.qna.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** A user's saved questions, newest first. */
@Table("question_saves_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionSaveByUserEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "question_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID questionId;

    @Column("collection_name") private String collectionName;
}
