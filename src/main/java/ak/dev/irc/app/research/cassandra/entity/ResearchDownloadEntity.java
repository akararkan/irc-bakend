package ak.dev.irc.app.research.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("research_downloads_by_research")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchDownloadEntity {

    @PrimaryKeyColumn(name = "research_id", type = PrimaryKeyType.PARTITIONED)
    private UUID researchId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "download_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID downloadId;

    @Column("user_id")    private UUID   userId;
    @Column("media_id")   private UUID   mediaId;
    @Column("ip_address") private String ipAddress;
}
