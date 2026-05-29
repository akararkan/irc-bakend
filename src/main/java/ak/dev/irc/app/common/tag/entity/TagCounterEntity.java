package ak.dev.irc.app.common.tag.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Per-(scope, tag) usage counter — the source of truth for trending.
 *
 * <p>{@code scope} is either {@code "ALL"} or a {@link ak.dev.irc.app.common.tag.ContentType}
 * name. Each tag use increments two rows (its type + {@code ALL}), so all tags
 * for a scope live in one partition and can be read in a single query when the
 * trending snapshot is recomputed.</p>
 */
@Table("tag_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TagCounterEntity {

    @PrimaryKeyColumn(name = "scope", type = PrimaryKeyType.PARTITIONED)
    private String scope;

    @PrimaryKeyColumn(name = "tag", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private String tag;

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("usage_count")
    private Long usageCount;
}
