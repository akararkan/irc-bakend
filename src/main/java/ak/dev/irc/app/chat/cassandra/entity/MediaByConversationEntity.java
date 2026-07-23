package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared-media gallery index — one partition per {@code (conversation, kind)}
 * clustered newest-first, so "all photos in this channel" is a single-partition
 * slice with no timeline scan. A row is written per attachment on send (plus a
 * {@code LINK} row when the body contains a URL) and removed on delete.
 */
@Table("media_by_conversation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaByConversationEntity {

    @PrimaryKeyColumn(name = "conversation_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private UUID conversationId;

    /** IMAGE | VIDEO | VOICE | AUDIO | GIF | STICKER | FILE | LINK */
    @PrimaryKeyColumn(name = "kind", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private String kind;

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.CLUSTERED, ordinal = 2,
                      ordering = Ordering.DESCENDING)
    private Long messageId;

    /** Position within the message's media album (0 for LINK rows). */
    @PrimaryKeyColumn(name = "media_index", type = PrimaryKeyType.CLUSTERED, ordinal = 3)
    private Integer mediaIndex;

    @Column("created_at")
    private Instant createdAt;
}
