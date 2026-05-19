package ak.dev.irc.app.research.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("research_views_by_research")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchViewEntity {

    @PrimaryKeyColumn(name = "research_id", type = PrimaryKeyType.PARTITIONED)
    private UUID researchId;

    @PrimaryKeyColumn(name = "user_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID userId;

    @Column("first_viewed_at") private Instant firstViewedAt;
}
