package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_token_user",  columnList = "user_id"),
        @Index(name = "idx_refresh_token_value", columnList = "token")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_refresh_token_user"))
    private User user;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "device_info", length = 300)
    private String deviceInfo;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private boolean isRevoked = false;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ── Session metadata (spec §2.4 / §12) ──────────────────────────────────
    // Additive: this row is the source of truth for the Active Sessions screen.
    // All columns are nullable so existing tokens keep validating unchanged;
    // new sessions populate them. The raw-token storage above is intentionally
    // preserved to keep the existing refresh flow working.

    /** Stable session id — carried as the {@code sid} claim on the access token
     *  so a single session can be pinpointed for revoke / step-up / denylist. */
    @Column(name = "sid")
    private UUID sid;

    /** Human-readable device label, e.g. "iPhone 15" or "Chrome on macOS". */
    @Column(name = "device_name", length = 120)
    private String deviceName;

    /** IOS | ANDROID | WEB | DESKTOP. */
    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** Set when the session is revoked (mirrors {@link #isRevoked} with a timestamp). */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** A trusted device may skip the 2FA prompt until this instant. */
    @Column(name = "trusted_until")
    private LocalDateTime trustedUntil;

    /** Stable hash of (platform, app-install-id, model) — NOT the IP. */
    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isRevoked && !isExpired();
    }

    public boolean isTrustedNow() {
        return trustedUntil != null && LocalDateTime.now().isBefore(trustedUntil);
    }
}
