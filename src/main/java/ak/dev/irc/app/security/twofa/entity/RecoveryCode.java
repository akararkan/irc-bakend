package ak.dev.irc.app.security.twofa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single-use 2FA recovery code (spec §12). 10 are generated at once, displayed
 * once, and stored only as a hash. Consumed atomically; regenerating a set
 * invalidates the entire previous set.
 */
@Entity
@Table(name = "recovery_codes",
       indexes = @Index(name = "idx_recovery_code_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Hash of the code (spec wants Argon2id; this offline build hashes with the
     *  platform's configured PasswordEncoder — BCrypt — see RecoveryCodeService). */
    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
