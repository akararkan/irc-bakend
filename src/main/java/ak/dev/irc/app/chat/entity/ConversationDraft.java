package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.dto.request.MediaRefDto;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * A per-user unsent draft for one conversation (Telegram drafts). One row per
 * (user, conversation); saving overwrites, sending/clearing deletes.
 */
@Entity
@Table(
    name = "conversation_drafts",
    uniqueConstraints = @UniqueConstraint(name = "uk_draft_member",
                                          columnNames = {"user_id", "conversation_id"}),
    indexes = @Index(name = "idx_draft_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationDraft extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Pre-uploaded attachments waiting in the draft. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media", columnDefinition = "jsonb")
    private List<MediaRefDto> media;

    /** Snowflake id of the message being replied to, carried by the draft. */
    @Column(name = "reply_to_id")
    private Long replyToId;
}
