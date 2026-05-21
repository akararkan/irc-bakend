package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/** Per-user unread notification counter. Written via CounterService. */
@Table("notification_unread_counter")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationUnreadCounterEntity {

    @PrimaryKey
    @Column("user_id")
    private UUID userId;

    @CassandraType(type = CassandraType.Name.COUNTER) @Column("unread") private Long unread;
}
