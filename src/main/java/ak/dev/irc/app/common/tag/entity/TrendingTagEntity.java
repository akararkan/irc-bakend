package ak.dev.irc.app.common.tag.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Pre-ranked trending snapshot, rebuilt periodically by {@code TrendingTagJob}.
 *
 * <p>Cassandra cannot {@code ORDER BY} a counter column or sort across
 * partitions, so the job sorts {@code tag_counters} in memory and writes the
 * top-K here as plain rows clustered by {@code tag_rank}. Reading trending is
 * then a single ordered, bounded partition scan — the "fastest search" path for
 * the trending strip. {@code usage_count} is a flattened snapshot of the
 * counter value at compute time.</p>
 */
@Table("trending_tags")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TrendingTagEntity {

    @PrimaryKeyColumn(name = "scope", type = PrimaryKeyType.PARTITIONED)
    private String scope;

    @PrimaryKeyColumn(name = "tag_rank", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Integer tagRank;

    @Column("tag")         private String tag;
    @Column("usage_count") private Long   usageCount;
}
