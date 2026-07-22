package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A Telegram-style group invite link. Only a SHA-256 hash of the opaque token is
 * stored — the plaintext token is shown once at creation and never persisted, so
 * a database leak can't reconstruct working links (the token itself is 64 hex
 * chars of UUID entropy, so an unsalted digest is not brute-forceable). Revoking rotates the token by
 * marking this row revoked and (optionally) issuing a new one.
 */
@Entity
@Table(
    name = "conversation_invites",
    uniqueConstraints = @UniqueConstraint(name = "uk_invite_token_hash", columnNames = "token_hash"),
    indexes = @Index(name = "idx_invite_conversation", columnList = "conversation_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationInvite extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** SHA-256 hash of the opaque token. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_by_user", nullable = false)
    private UUID createdByUser;

    /** Null = never expires. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Null = unlimited uses. */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private int useCount = 0;

    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private boolean revoked = false;

    public boolean isUsable() {
        if (revoked) return false;
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) return false;
        if (maxUses != null && useCount >= maxUses) return false;
        return true;
    }
}
