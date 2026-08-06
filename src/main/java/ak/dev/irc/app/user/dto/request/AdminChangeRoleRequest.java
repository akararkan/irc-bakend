package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.common.messages.UserMessages;
import ak.dev.irc.app.user.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin payload for promoting/demoting a user along the four-role ladder
 * ({@link Role#USER} / {@link Role#RESEARCHER} / {@link Role#SCHOLAR} /
 * {@link Role#ADMIN}).
 *
 * <p>The optional {@link #reason()} is captured in the user's audit trail
 * so future operators can see why a role was changed.</p>
 */
public record AdminChangeRoleRequest(
        @NotNull(message = UserMessages.VAL_ROLE_REQUIRED)
        Role role,

        @Size(max = 500, message = UserMessages.VAL_REASON_MAX_500)
        String reason
) {}
