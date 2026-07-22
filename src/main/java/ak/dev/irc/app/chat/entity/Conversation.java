package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Relational metadata for a conversation (DIRECT or GROUP). The message log
 * itself lives in Cassandra; this Postgres row answers "list my conversations",
 * "who owns this group", "what were the last-message preview + timestamp for the
 * inbox", and holds the denormalised pointers that make the inbox a pure Postgres
 * join with no Cassandra touch.
 */
@Entity
@Table(
    name = "conversations",
    uniqueConstraints = @UniqueConstraint(name = "uk_conversation_direct_key", columnNames = "direct_key"),
    indexes = {
        @Index(name = "idx_conversation_last_msg", columnList = "last_message_at DESC"),
        @Index(name = "idx_conversation_owner", columnList = "owner_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private ConversationType type;

    /** Null for DIRECT. */
    @Column(name = "title", length = 120)
    private String title;

    /** R2/S3 key for the group avatar (GROUP only). */
    @Column(name = "avatar_key", length = 255)
    private String avatarKey;

    /** The creator/owner. For DIRECT, an arbitrary-but-stable participant. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /**
     * Deterministic key {@code min(a,b):max(a,b)} for DIRECT conversations so the
     * {@code UNIQUE} constraint makes "get or create DM" race-safe. Null for GROUP
     * (Postgres permits many NULLs under a unique constraint).
     */
    @Column(name = "direct_key", length = 73)
    private String directKey;

    /** Snowflake id of the newest message — drives inbox ordering + gap sync. */
    @Column(name = "last_message_id")
    private Long lastMessageId;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 160)
    private String lastMessagePreview;

    /** Live member count — the fan-out strategy cutoff (eager unread ≤ 256). */
    @Column(name = "member_count", nullable = false)
    @Builder.Default
    private int memberCount = 0;

    /** GROUP settings as JSONB — add knobs without a migration. Null for DIRECT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_settings", columnDefinition = "jsonb")
    private GroupSettings groupSettings;

    /** Owner soft-delete of a group (hides it for everyone; message log retained). */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isGroup() {
        return type == ConversationType.GROUP;
    }

    public boolean isDirect() {
        return type == ConversationType.DIRECT;
    }
}
