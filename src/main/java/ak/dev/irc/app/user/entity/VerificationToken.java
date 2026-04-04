package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.TokenType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "verification_tokens",
    indexes = @Index(name = "idx_verification_token_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationToken extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_verification_token_user"))
    private User user;

    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private TokenType type;

    /** EMAIL_VERIFY: 24h · PASSWORD_RESET: 1h · TWO_FACTOR_OTP: 10min */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_used", nullable = false)
    @Builder.Default
    private boolean isUsed = false;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isUsed && !isExpired();
    }
}
