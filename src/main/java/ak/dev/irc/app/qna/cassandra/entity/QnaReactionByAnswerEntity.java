package ak.dev.irc.app.qna.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** "Did user U react to answer A?" point lookup. */
@Table("qna_reactions_by_answer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QnaReactionByAnswerEntity {

    @PrimaryKeyColumn(name = "answer_id", type = PrimaryKeyType.PARTITIONED)
    private UUID answerId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("reaction_type") private String  reactionType;
    @Column("created_at")    private Instant createdAt;
}
