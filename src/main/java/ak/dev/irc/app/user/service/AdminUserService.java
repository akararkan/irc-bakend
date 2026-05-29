package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.AdminChangeRoleRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;

import java.util.UUID;

/**
 * Administrative operations on user accounts that are NOT exposed to regular
 * users — currently just role changes (the previous AccountType / VerificationTier
 * dimensions were retired in favour of a single {@code role} field).
 */
public interface AdminUserService {

    /**
     * Set the target user's {@code role}. The reason (if supplied) is captured
     * in the user's audit trail. Side-effect: the auto-derived badge updates
     * with the role on the next read.
     */
    UserResponse changeRole(UUID targetUserId, AdminChangeRoleRequest request);
}
