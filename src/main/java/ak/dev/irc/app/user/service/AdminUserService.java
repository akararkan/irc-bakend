package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.AdminChangeAccountTypeRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;

import java.util.UUID;

/**
 * Administrative operations on user accounts that are NOT exposed to regular
 * users — most notably setting {@code AccountType} / {@code VerificationTier}.
 *
 * <p>Users can no longer request account-type changes themselves; all
 * promotions, downgrades, and tier adjustments flow through here.</p>
 */
public interface AdminUserService {

    /**
     * Set the target user's {@code accountType} (and optionally their
     * {@code verificationTier} and {@code role}). The reason is captured in
     * the user's audit trail.
     *
     * <p>Refuses to mutate the platform's own system account.</p>
     */
    UserResponse changeAccountType(UUID targetUserId, AdminChangeAccountTypeRequest request);
}
