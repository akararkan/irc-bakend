package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.dto.ChannelSettings;
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

    /** Group description / topic (GROUP only). */
    @Column(name = "description", length = 500)
    private String description;

    /** R2/S3 key for the group avatar (GROUP only). */
    @Column(name = "avatar_key", length = 255)
    private String avatarKey;

    /** Public @handle for a CHANNEL (unique; null for DM / GROUP / private channel). */
    @Column(name = "handle", length = 32, unique = true)
    private String handle;

    /** CHANNEL discoverability — public channels appear in discovery/search. */
    @Column(name = "is_public", nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean publicChannel = false;

    /** R2/S3 key for the channel cover/banner image (CHANNEL only). */
    @Column(name = "cover_key", length = 255)
    private String coverKey;

    /** Platform-granted verified badge (CHANNEL only; set by a platform admin). */
    @Column(name = "verified", nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean verified = false;

    /** Channel directory category slug (e.g. "news", "science"; CHANNEL only). */
    @Column(name = "category", length = 48)
    private String category;

    /** Denormalised count of live (non-deleted, non-system) channel posts. */
    @Column(name = "post_count", nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int postCount = 0;

    /** CHANNEL knobs as JSONB — reactions policy, protected content, signatures,
     *  join-by-request, … Null for DIRECT/GROUP (and legacy channels → defaults). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_settings", columnDefinition = "jsonb")
    private ChannelSettings channelSettings;

    /** CHANNEL only: the linked discussion GROUP where subscribers comment on
     *  posts (Telegram "discussion group"). Null = comments disabled. */
    @Column(name = "linked_group_id")
    private UUID linkedGroupId;

    /**
     * Disappearing-messages timer in seconds (0 = off). When &gt; 0, every new
     * message is written to Cassandra with this TTL so it auto-deletes after the
     * window — WhatsApp "disappearing messages" / Telegram auto-delete.
     */
    @Column(name = "disappearing_seconds", nullable = false,
            columnDefinition = "integer not null default 0")
    @Builder.Default
    private int disappearingSeconds = 0;

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

    public boolean isChannel() {
        return type == ConversationType.CHANNEL;
    }

    /** Channel settings, falling back to defaults for legacy rows (null column). */
    public ChannelSettings channelSettingsOrDefaults() {
        return channelSettings != null ? channelSettings : ChannelSettings.defaults();
    }
}
