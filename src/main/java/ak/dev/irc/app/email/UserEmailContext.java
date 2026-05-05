package ak.dev.irc.app.email;

import java.io.Serializable;
import java.util.UUID;

/**
 * Slim projection used by the email pipeline — just the columns needed to
 * decide whether to send and where. Loading this instead of the full
 * {@code User} entity cuts the hot-path read from ~30 columns + JPA proxy
 * setup to one tight row, and lets us cache cleanly.
 *
 * <p>{@link Serializable} for the Redis cache layer.</p>
 */
public record UserEmailContext(
        UUID userId,
        String email,
        String fullName,
        boolean emailNotificationsEnabled,
        boolean emailSocialEnabled,
        boolean emailMentionsEnabled,
        boolean emailSystemEnabled
) implements Serializable {
}
