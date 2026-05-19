package ak.dev.irc.app.research.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** "Did user U react to research R?" point lookup. */
@Table("research_reactions_by_research")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchReactionByResearchEntity {

    @PrimaryKeyColumn(name = "research_id", type = PrimaryKeyType.PARTITIONED)
    private UUID researchId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("reaction_type") private String  reactionType;
    @Column("created_at")    private Instant createdAt;
}
