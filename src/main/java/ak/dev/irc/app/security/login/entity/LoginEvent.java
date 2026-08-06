package ak.dev.irc.app.security.login.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only login history (spec §12). One row per login attempt: timestamp,
 * IP, coarse geo, user agent, method and outcome. A new-device or new-country
 * login triggers the security alert path (which bypasses DND and channel prefs).
 */
@Entity
@Table(name = "login_events",
       indexes = {
           @Index(name = "idx_login_event_user", columnList = "user_id, ts"),
           @Index(name = "idx_login_event_outcome", columnList = "user_id, outcome"),
           @Index(name = "idx_login_event_ip", columnList = "ip, ts")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    @Column(name = "ip", length = 45)
    private String ip;

    /** Coarse geo (country/region), never precise — best-effort. */
    @Column(name = "coarse_geo", length = 80)
    private String coarseGeo;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    /** PASSWORD | OTP | REFRESH | TWO_FA. */
    @Column(name = "method", length = 20)
    private String method;

    /** SUCCESS | FAILED | LOCKED. */
    @Column(name = "outcome", length = 20)
    private String outcome;

    @PrePersist
    void onCreate() {
        if (ts == null) ts = LocalDateTime.now();
    }
}
