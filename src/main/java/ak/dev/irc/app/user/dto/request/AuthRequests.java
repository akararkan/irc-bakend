package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.common.messages.AuthMessages;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * All authentication-related request DTOs.
 */
public final class AuthRequests {

    private AuthRequests() {}

    public record LoginRequest(
            @NotBlank(message = AuthMessages.VAL_LOGIN_IDENTIFIER_REQUIRED)
            String username,   // accepts either username or email

            @NotBlank(message = AuthMessages.VAL_PASSWORD_REQUIRED)
            String password
    ) {}

    public record RegisterRequest(
            @NotBlank(message = AuthMessages.VAL_FIRST_NAME_REQUIRED)
            @Size(max = 80, message = AuthMessages.VAL_FIRST_NAME_MAX)
            String fname,

            @NotBlank(message = AuthMessages.VAL_LAST_NAME_REQUIRED)
            @Size(max = 80, message = AuthMessages.VAL_LAST_NAME_MAX)
            String lname,

            @NotBlank(message = AuthMessages.VAL_USERNAME_REQUIRED)
            @Size(min = 3, max = 50, message = AuthMessages.VAL_USERNAME_SIZE)
            String username,

            @NotBlank(message = AuthMessages.VAL_EMAIL_REQUIRED)
            @Email(message = AuthMessages.VAL_EMAIL_INVALID)
            String email,

            @NotBlank(message = AuthMessages.VAL_PASSWORD_REQUIRED)
            @Size(min = 8, max = 128, message = AuthMessages.VAL_PASSWORD_SIZE)
            String password
    ) {}

    /**
     * Second leg of a two-factor login: the challenge handed back by
     * {@code /auth/login} plus either the authenticator's 6-digit code or one of
     * the account's single-use recovery codes.
     */
    public record TwoFactorLoginRequest(
            @NotBlank(message = AuthMessages.VAL_MFA_TOKEN_REQUIRED)
            String mfaToken,

            @NotBlank(message = AuthMessages.VAL_MFA_CODE_REQUIRED)
            String code
    ) {}

    public record RefreshTokenRequest(
            String refreshToken  // optional — can come from cookie instead
    ) {}

    public record LogoutRequest(
            String refreshToken  // optional — can come from cookie instead
    ) {}

    /**
     * The authenticated user changes their own password. There is intentionally
     * no "forgot password" / reset-token flow — only an authenticated session
     * can rotate the credential. The current password must be re-verified to
     * defend against session hijack scenarios.
     */
    public record ChangePasswordRequest(
            @NotBlank(message = AuthMessages.VAL_CURRENT_PASSWORD_REQUIRED)
            String currentPassword,

            @NotBlank(message = AuthMessages.VAL_NEW_PASSWORD_REQUIRED)
            @Size(min = 8, max = 128, message = AuthMessages.VAL_NEW_PASSWORD_SIZE)
            String newPassword
    ) {}
}
