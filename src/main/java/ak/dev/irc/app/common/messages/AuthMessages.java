package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the authentication flow ({@code app/user}:
 * {@code AuthServiceImpl} + {@code AuthRequests} validation). Catalog:
 * docs/errors/user-facing-messages.md §1.1–1.2 {@code user/auth}. Follows the
 * registry conventions — see {@link ak.dev.irc.app.common.messages} package
 * javadoc.
 */
public final class AuthMessages {

    private AuthMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String AUTH_REFRESH_TOKEN_MISSING        = "AUTH_REFRESH_TOKEN_MISSING";
    public static final String AUTH_REFRESH_TOKEN_INVALID        = "AUTH_REFRESH_TOKEN_INVALID";
    public static final String AUTH_WRONG_TOKEN_TYPE             = "AUTH_WRONG_TOKEN_TYPE";
    public static final String AUTH_REFRESH_TOKEN_NOT_FOUND      = "AUTH_REFRESH_TOKEN_NOT_FOUND";
    public static final String AUTH_REFRESH_TOKEN_REUSED         = "AUTH_REFRESH_TOKEN_REUSED";
    public static final String AUTH_REFRESH_TOKEN_EXPIRED        = "AUTH_REFRESH_TOKEN_EXPIRED";
    public static final String AUTH_REQUIRED                     = "AUTH_REQUIRED";
    public static final String USER_NOT_FOUND                    = "USER_NOT_FOUND";
    public static final String AUTH_CURRENT_PASSWORD_INVALID     = "AUTH_CURRENT_PASSWORD_INVALID";
    public static final String AUTH_NEW_PASSWORD_SAME_AS_CURRENT = "AUTH_NEW_PASSWORD_SAME_AS_CURRENT";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String AUTH_REFRESH_TOKEN_MISSING_MSG =
            "No refresh token provided. Include it in the request body or as a cookie.";
    public static final String AUTH_REFRESH_TOKEN_INVALID_MSG =
            "Refresh token is invalid or has expired. Please log in again.";
    public static final String AUTH_WRONG_TOKEN_TYPE_MSG =
            "The provided token is not a refresh token.";
    public static final String AUTH_REFRESH_TOKEN_NOT_FOUND_MSG =
            "Refresh token not recognised. It may have been revoked.";
    public static final String AUTH_REFRESH_TOKEN_REUSED_MSG =
            "This refresh token has been revoked. All sessions terminated for security. "
                    + "Please log in again.";
    public static final String AUTH_REFRESH_TOKEN_EXPIRED_MSG =
            "Refresh token has expired. Please log in again.";
    /** Default-code (AUTH_UNAUTHORIZED) variant used on logout-all. */
    public static final String AUTH_NOT_AUTHENTICATED_MSG =
            "Not authenticated";
    public static final String AUTH_REQUIRED_MSG =
            "Not authenticated.";
    public static final String USER_NOT_FOUND_MSG =
            "User no longer exists.";
    public static final String AUTH_CURRENT_PASSWORD_INVALID_MSG =
            "Current password is incorrect.";
    public static final String AUTH_NEW_PASSWORD_SAME_AS_CURRENT_MSG =
            "New password must be different from the current password.";

    // ── validation copy (plain literals — annotation values) ────────────
    public static final String VAL_LOGIN_IDENTIFIER_REQUIRED = "Username or email is required";
    public static final String VAL_PASSWORD_REQUIRED         = "Password is required";
    public static final String VAL_FIRST_NAME_REQUIRED       = "First name is required";
    public static final String VAL_FIRST_NAME_MAX            = "First name must be at most 80 characters";
    public static final String VAL_LAST_NAME_REQUIRED        = "Last name is required";
    public static final String VAL_LAST_NAME_MAX             = "Last name must be at most 80 characters";
    public static final String VAL_USERNAME_REQUIRED         = "Username is required";
    public static final String VAL_USERNAME_SIZE             = "Username must be between 3 and 50 characters";
    public static final String VAL_EMAIL_REQUIRED            = "Email is required";
    public static final String VAL_EMAIL_INVALID             = "Must be a valid email address";
    public static final String VAL_PASSWORD_SIZE             = "Password must be between 8 and 128 characters";
    public static final String VAL_CURRENT_PASSWORD_REQUIRED = "Current password is required";
    public static final String VAL_NEW_PASSWORD_REQUIRED     = "New password is required";
    public static final String VAL_NEW_PASSWORD_SIZE         = "New password must be between 8 and 128 characters";
}
