package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The core message log — <b>one partition per {@code (conversation_id, bucket)}</b>,
 * clustered by the Snowflake {@code message_id} DESC so the newest message is
 * first and a page is a single-partition slice.
 *
 * <p>Reading the latest page or an older page both touch exactly one partition:
 * <pre>
 *   SELECT * FROM messages_by_conversation
 *   WHERE conversation_id = ? AND bucket = ? [AND message_id &lt; ?]
 *   LIMIT ?;
 * </pre>
 * The bucket comes straight from the Snowflake timestamp
 * ({@link ak.dev.irc.app.chat.util.ChatBuckets#bucketOf(long)}), so writer and
 * reader agree with no stored coupling and the reader walks a known, bounded
 * bucket range.</p>
 */
@Table("messages_by_conversation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageByConversationEntity {

    @PrimaryKeyColumn(name = "conversation_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private UUID conversationId;

    @PrimaryKeyColumn(name = "bucket", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private Integer bucket;

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.CLUSTERED, ordinal = 2,
                      ordering = Ordering.DESCENDING)
    private Long messageId;

    @Column("sender_id")       private UUID senderId;
    /** TEXT | IMAGE | VIDEO | VOICE | FILE | SYSTEM */
    @Column("type")            private String type;
    /** Null for pure-media messages. */
    @Column("body")            private String body;
    @Column("media")           private List<MediaRef> media;
    /** Snowflake id of the replied-to message, nullable. */
    @Column("reply_to_id")     private Long replyToId;
    /** Source conversation for a forwarded message, nullable. */
    @Column("forwarded_from")  private UUID forwardedFrom;
    @Column("mentions")        private Set<UUID> mentions;
    @Column("edited_at")       private Instant editedAt;
    /** Tombstone flag — the row stays so ordering/pagination don't break. */
    @Column("deleted")         private Boolean deleted;
    /** For SYSTEM messages: MEMBER_ADDED, TITLE_CHANGED, … */
    @Column("system_event")    private String systemEvent;
    @Column("created_at")      private Instant createdAt;
}
