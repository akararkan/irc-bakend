package ak.dev.irc.app.settings.privacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-user field visibility policy (spec §5). One JSONB column
 * {@code privacy = { "BIO": "FRIENDS", "BIRTHDAY": "ONLY_ME", ... }} keyed by
 * {@link ak.dev.irc.app.settings.privacy.enums.FieldKey} name. A JSONB column is
 * preferred over 25 boolean columns because the key set grows every release and
 * a migration per key is not sustainable. A missing key resolves to the code
 * default ({@code PrivacyDefaults}), never null.
 */
@Entity
@Table(name = "user_privacy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPrivacy {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    /** FieldKey.name() → VisibilityLevel.name(). Stored as raw strings for forward-compat. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privacy", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> policy = new HashMap<>();

    public static UserPrivacy emptyFor(UUID userId) {
        return UserPrivacy.builder().userId(userId).policy(new HashMap<>()).build();
    }
}
