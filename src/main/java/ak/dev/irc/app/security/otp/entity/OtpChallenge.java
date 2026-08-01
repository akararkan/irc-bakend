package ak.dev.irc.app.security.otp.entity;

import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit record of an OTP challenge (spec §2.4). Redis holds the live code with a
 * TTL (the fast path + free expiry); this Postgres row is the durable audit
 * trail — who requested a code, when, from where, and how many attempts.
 *
 * <p>The plaintext code is NEVER stored — only {@code codeHash =
 * HMAC-SHA256(code, pepper)}. The {@code destinationHash} likewise hides the
 * raw phone number.</p>
 */
@Entity
@Table(name = "otp_challenges",
       indexes = {
           @Index(name = "idx_otp_dest", columnList = "destination_hash, purpose"),
           @Index(name = "idx_otp_expires", columnList = "expires_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** {@code HMAC-SHA256(E164, pepper)} — never the raw destination. */
    @Column(name = "destination_hash", nullable = false, length = 64)
    private String destinationHash;

    /** {@code HMAC-SHA256(code, pepper)}. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private OtpPurpose purpose;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired()  { return LocalDateTime.now().isAfter(expiresAt); }
}
