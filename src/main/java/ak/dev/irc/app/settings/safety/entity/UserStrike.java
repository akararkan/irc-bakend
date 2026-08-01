package ak.dev.irc.app.settings.safety.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A moderation strike against a user (spec §18). Strikes accumulate with decay
 * (they expire after 90 days) and thresholds drive automated restrictions. Each
 * strike stores the linked report id so an appeal can be reviewed against real
 * evidence.
 */
@Entity
@Table(name = "user_strikes",
       indexes = @Index(name = "idx_strike_user", columnList = "user_id, expires_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStrike {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The report that produced this strike, if any. */
    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    /** Decay point — {@code issuedAt + 90 days}. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) issuedAt = LocalDateTime.now();
        if (expiresAt == null) expiresAt = issuedAt.plusDays(90);
    }

    public boolean isActive() {
        return expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
