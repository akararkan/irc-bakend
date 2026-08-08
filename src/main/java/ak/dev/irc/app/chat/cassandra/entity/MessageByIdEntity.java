package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lookup of a single message by its Snowflake id alone — needed by replies,
 * forwards, "jump to message", and (critically) edit/delete, because a Cassandra
 * mutation of {@link MessageByConversationEntity} requires the full primary key
 * {@code (conversation_id, bucket, message_id)} which this row supplies.
 *
 * <p>Denormalisation is normal and correct in Cassandra — this row is written in
 * the same logical operation as the main insert. It carries {@code conversation_id}
 * and {@code bucket} so the twin mutation on the log table can be issued without
 * a second lookup.</p>
 */
@Table("message_by_id")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageByIdEntity {

    @PrimaryKey
    @Column("message_id")
    private Long messageId;

    @Column("conversation_id") private UUID conversationId;
    @Column("bucket")          private Integer bucket;
    @Column("sender_id")       private UUID senderId;
    @Column("type")            private String type;
    @Column("body")            private String body;
    @Column("media")           private List<MediaRef> media;
    @Column("reply_to_id")     private Long replyToId;
    @Column("forwarded_from")  private UUID forwardedFrom;
    @Column("mentions")        private Set<UUID> mentions;
    /** Lowercased #hashtags extracted from the body/caption. */
    @Column("tags")            private Set<String> tags;
    /** Posting admin's display label on channel posts when "sign messages" is on. */
    @Column("author_signature") private String authorSignature;
    /** JSON poll payload for {@code POLL} messages (question/options/flags). */
    @Column("poll")            private String poll;
    /** JSON location payload for {@code LOCATION} messages. */
    @Column("location")        private String location;
    /** JSON contact payload for {@code CONTACT} messages. */
    @Column("contact")         private String contact;
    @Column("deleted")         private Boolean deleted;
    @Column("edited_at")       private Instant editedAt;
    @Column("system_event")    private String systemEvent;
    @Column("created_at")      private Instant createdAt;
    /** Twin of {@code messages_by_conversation.moderation_status} — the applier
     *  resolves a message through this table, so it needs the same flag. */
    @Column("moderation_status") private String moderationStatus;

    /**
     * True once the message has actually been fanned out — broadcast, bells,
     * inbox preview, gallery rows, channel counters.
     *
     * <p>Not derivable from {@code moderationStatus} or {@code editedAt}: a
     * message held at send and later edited looks identical to one that was
     * delivered and then edited into a hold, and the two need opposite
     * treatment when the verdict lands — first delivery versus an edit
     * broadcast. Without this flag the first case is never delivered at all:
     * readable on refresh, but no recipient is ever told it arrived.</p>
     *
     * <p>NULL means the row predates moderation, and those were all
     * delivered.</p>
     */
    @Column("delivered") private Boolean delivered;
}
