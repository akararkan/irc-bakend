package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Interest-graph signal for home-feed ranking: one counter per
 * (viewer, author) pair, bumped every time the viewer positively engages
 * with the author's content (view +1, like +3, save +4, comment +5,
 * share +5 — weights applied at write time so one counter carries the
 * whole weighted history).
 *
 * <p>Reads are a single-partition slice with a clustering IN — the feed
 * ranker asks "how much does viewer V care about these ~30 authors" in
 * one round-trip. Negative actions (unlike, unsave) do NOT decrement:
 * affinity models long-term demonstrated interest, and a like-then-unlike
 * still proved the viewer stopped on that author's content.</p>
 *
 * <p>Counter column — CANNOT be written via save(); only via
 * {@code UPDATE … SET interactions = interactions + N} raw CQL
 * (see {@code AuthorAffinityService}).</p>
 */
@Table("user_author_affinity")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAuthorAffinityEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "author_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID authorId;

    @CassandraType(type = CassandraType.Name.COUNTER)
    @Column("interactions")
    private Long interactions;
}
