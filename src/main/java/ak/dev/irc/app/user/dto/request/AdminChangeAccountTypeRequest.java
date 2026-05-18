package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.AccountType;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.enums.VerificationTier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin-only payload to set a target user's account classification.
 *
 * <p>{@code accountType} is required; {@code verificationTier} and {@code role}
 * are optional companions for setting scholar privileges in the same call. A
 * non-null {@code reason} is recorded in the audit log.</p>
 */
public record AdminChangeAccountTypeRequest(

        @NotNull(message = "accountType is required")
        AccountType accountType,

        /** Optional — leave null to keep the user's existing tier. */
        VerificationTier verificationTier,

        /**
         * Optional — leave null to keep the user's existing role. Set to
         * {@link Role#SCHOLAR} or {@link Role#RESEARCHER} when promoting an
         * account that should also gain those privileges.
         */
        Role role,

        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason

) {}
