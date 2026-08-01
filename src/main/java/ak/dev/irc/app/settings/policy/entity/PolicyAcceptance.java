package ak.dev.irc.app.settings.policy.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records that a user accepted a given policy document at a given version
 * (spec §19), so a re-consent prompt can be triggered when a policy changes
 * materially. Composite key {@code (userId, policyKey)} — one current
 * acceptance per policy, upserted on re-accept.
 */
@Entity
@Table(name = "policy_acceptances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyAcceptance {

    @EmbeddedId
    private PolicyAcceptanceId id;

    @Column(name = "version", nullable = false, length = 40)
    private String version;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (acceptedAt == null) acceptedAt = LocalDateTime.now();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @EqualsAndHashCode
    public static class PolicyAcceptanceId implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;
        @Column(name = "policy_key", nullable = false, length = 40)
        private String policyKey;
    }
}
