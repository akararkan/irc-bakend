package ak.dev.irc.app.security.otp.enums;

/**
 * Why an OTP challenge was issued (spec §2, §4, §22.3). The purpose scopes a
 * code so a code minted for one flow cannot be replayed into another.
 */
public enum OtpPurpose {
    /** Phone-number login / registration. */
    LOGIN,
    /** Verifying a phone number a logged-in user is adding. */
    PHONE_VERIFY,
    /** Sensitive change: moving to a new phone number. */
    PHONE_CHANGE,
    /** Sensitive change: moving to a new email address. */
    EMAIL_CHANGE,
    /** Password reset via OTP. */
    PASSWORD_RESET,
    /** Fresh-auth challenge for a sensitive action inside a valid session. */
    STEP_UP;

    public static OtpPurpose parse(String raw, OtpPurpose fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
