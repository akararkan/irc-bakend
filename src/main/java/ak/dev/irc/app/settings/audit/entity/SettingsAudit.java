package ak.dev.irc.app.settings.audit.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit trail for privacy- and security-relevant settings mutations
 * (spec §22.3). When a user reports "my account was public and I never changed
 * it", this table is the only way to answer.
 *
 * <p>Deliberately a dedicated table rather than the generic {@code AuditLog}
 * because it captures the specific {@code (key, oldValue, newValue)} shape the
 * spec mandates and is queried per-user for the "who changed what" view.</p>
 */
@Entity
@Table(name = "settings_audit",
       indexes = {
           @Index(name = "idx_settings_audit_user", columnList = "user_id, created_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettingsAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Dotted setting path, e.g. {@code privacy.bio} or {@code security.2fa}. */
    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
