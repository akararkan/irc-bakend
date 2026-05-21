package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/** Counter table — use_count is bumped each time a post adopts the sound. */
@Table("sound_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SoundCounterEntity {

    @PrimaryKey
    @Column("sound_id")
    private UUID soundId;

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("use_count") private Long useCount;
}
