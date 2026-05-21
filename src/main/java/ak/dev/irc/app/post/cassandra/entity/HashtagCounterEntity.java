package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/** Per-hashtag use counter — feeds the trending-tags ranker. */
@Table("hashtag_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HashtagCounterEntity {

    @PrimaryKey
    private String hashtag;

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("post_count") private Long postCount;
}
