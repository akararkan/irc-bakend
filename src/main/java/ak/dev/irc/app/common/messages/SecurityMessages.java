package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the security module ({@code app/security}).
 * Catalog: docs/errors/user-facing-messages.md §1.22–§1.31 (`security`,
 * `security/jwt`, `security/otp`, `security/phone`, `security/stepup`,
 * `security/twofa`) and the notification/SMS catalog rows. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class SecurityMessages {

    private SecurityMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String AUTH_REQUIRED        = "AUTH_REQUIRED";
    public static final String ACCESS_DENIED        = "ACCESS_DENIED";
    public static final String TWO_FA_ALREADY_ON    = "TWO_FA_ALREADY_ON";
    public static final String TWO_FA_NOT_STARTED   = "TWO_FA_NOT_STARTED";
    public static final String TWO_FA_INVALID       = "TWO_FA_INVALID";
    public static final String OTP_INVALID          = "OTP_INVALID";
    public static final String PHONE_REQUIRED       = "PHONE_REQUIRED";
    public static final String PHONE_INVALID        = "PHONE_INVALID";
    public static final String STEP_UP_BAD_PASSWORD = "STEP_UP_BAD_PASSWORD";
    public static final String STEP_UP_REQUIRED     = "STEP_UP_REQUIRED";

    // ── message text (templates render with .formatted) ─────────────────
    /** {@code SecurityUtils.requireCurrentUserId} variant of {@code AUTH_REQUIRED}. */
    public static final String AUTH_REQUIRED_MSG =
            "Authentication is required for this endpoint.";
    /** {@code JwtAuthenticationEntryPoint} variant of {@code AUTH_REQUIRED}. */
    public static final String AUTH_REQUIRED_ENTRY_POINT_MSG =
            "You must be authenticated to access this resource. "
                    + "Please log in or provide a valid token.";
    public static final String ACCESS_DENIED_MSG =
            "You do not have the required permissions to access this resource.";
    public static final String TWO_FA_ALREADY_ON_MSG =
            "Two-factor authentication is already enabled.";
    public static final String TWO_FA_NOT_STARTED_MSG =
            "Start 2FA setup first.";
    public static final String TWO_FA_INVALID_MSG =
            "Invalid authentication code.";
    public static final String OTP_INVALID_MSG =
            "Invalid or expired verification code.";
    public static final String PHONE_REQUIRED_MSG =
            "Phone number is required.";
    /** {@code PHONE_INVALID} — input contains no digits. */
    public static final String PHONE_INVALID_NO_DIGITS_MSG =
            "Phone number has no digits.";
    /** {@code PHONE_INVALID} — normalized number outside E.164 length bounds. */
    public static final String PHONE_INVALID_LENGTH_MSG =
            "Phone number is not a valid E.164 length.";
    public static final String STEP_UP_BAD_PASSWORD_MSG =
            "Password is incorrect.";
    public static final String STEP_UP_REQUIRED_MSG =
            "This action requires you to confirm your identity.";

    // ── notification / SMS copy ─────────────────────────────────────────
    /** Title of the new-device sign-in security alert (body stays dynamic at the call site). */
    public static final String NOTIF_NEW_DEVICE_TITLE =
            "New sign-in to your account";
    /** OTP SMS body — args: code, minutes-to-expiry. */
    public static final String NOTIF_OTP_SMS =
            "Your IRC verification code is %s. It expires in %s minutes.";
}
