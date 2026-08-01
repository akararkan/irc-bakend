package ak.dev.irc.app.settings.presence.entity;

import ak.dev.irc.app.settings.presence.enums.PresencePolicy;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Three-way presence visibility policy (spec §7). Refines the existing boolean
 * {@code ChatUserSettings.lastSeenVisible} into Everyone/Friends/Nobody.
 *
 * <p>The current presence enforcement in {@code PresenceService} reads the
 * boolean; this row is the richer source of truth. The
 * {@code PresencePolicyService} mirrors {@code NOBODY} onto that boolean so the
 * existing hot-path enforcement immediately honours a full hide; the FRIENDS
 * refinement is applied by {@code PresencePolicyService.lastSeenVisibleTo}.</p>
 */
@Entity
@Table(name = "user_presence_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPresencePolicy {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "online_status_policy", nullable = false, length = 16,
            columnDefinition = "varchar(16) not null default 'EVERYONE'")
    @Builder.Default
    private PresencePolicy onlineStatusPolicy = PresencePolicy.EVERYONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_seen_policy", nullable = false, length = 16,
            columnDefinition = "varchar(16) not null default 'EVERYONE'")
    @Builder.Default
    private PresencePolicy lastSeenPolicy = PresencePolicy.EVERYONE;

    public static UserPresencePolicy defaultsFor(UUID userId) {
        return UserPresencePolicy.builder().userId(userId).build();
    }
}
