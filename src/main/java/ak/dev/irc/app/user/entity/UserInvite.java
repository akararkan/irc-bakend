package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.user.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-issued onboarding invite (user-administration.md §3.4) — the platform
 * previously had no user-onboarding invite of any kind (only channel invites).
 * Consuming the opaque token creates the account pre-verified with the
 * invited role via the shared provisioning path.
 */
@Entity
@Table(name = "user_invites",
        indexes = {
                @Index(name = "idx_user_invite_email", columnList = "email"),
                @Index(name = "idx_user_invite_token", columnList = "token", unique = true)
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Opaque URL-safe token — single active use. */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (expiresAt == null) expiresAt = createdAt.plusDays(7);
    }

    public boolean isUsable() {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }
}
