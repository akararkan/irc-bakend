package ak.dev.irc.app.settings.consent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only consent evidence (spec §14). Each grant or revocation of a
 * data-sharing device permission (contacts, location, photos, …) is recorded
 * here with a timestamp and the app version that produced it.
 *
 * <p>This is what lets the platform demonstrate that contact synchronization
 * (§3) was consented to, and exactly when it was withdrawn. Rows are never
 * updated — a state change is a new row.</p>
 */
@Entity
@Table(name = "consent_events",
       indexes = {
           @Index(name = "idx_consent_user_time", columnList = "user_id, occurred_at"),
           @Index(name = "idx_consent_user_scope", columnList = "user_id, scope")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Permission scope, e.g. {@code CONTACTS}, {@code LOCATION}, {@code PHOTOS}. */
    @Column(name = "scope", nullable = false, length = 60)
    private String scope;

    /** True for a grant, false for a revocation. */
    @Column(name = "granted", nullable = false)
    private boolean granted;

    /** Client app version at the time of the grant/revocation (optional). */
    @Column(name = "app_version", length = 40)
    private String appVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = LocalDateTime.now();
    }
}
