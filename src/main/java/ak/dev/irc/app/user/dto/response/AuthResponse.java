package ak.dev.irc.app.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * Returned on successful login, registration, and token refresh.
 * <p>
 * Tokens are also set as HttpOnly cookies by the controller/service,
 * but are included in the JSON body so API clients (mobile, Postman)
 * can use them directly as Bearer tokens.
 * </p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long   expiresIn;     // access token TTL in seconds
    private final UserResponse user;    // null on refresh — only tokens change

    /**
     * {@code true} when the password was correct but the account is protected by
     * two-factor authentication: <b>no session was issued</b>. The client must
     * post {@code mfaToken} plus the user's 6-digit code (or a recovery code) to
     * {@code POST /api/v1/auth/login/2fa} to finish signing in. Omitted entirely
     * (never {@code false}) on ordinary logins.
     */
    private final Boolean mfaRequired;

    /** Opaque, single-use, short-TTL handle for the pending 2FA step. */
    private final String mfaToken;

    public static AuthResponse ofTokens(String access, String refresh,
                                         long expiresInMs, UserResponse user) {
        return AuthResponse.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .tokenType("Bearer")
                .expiresIn(expiresInMs / 1000)
                .user(user)
                .build();
    }

    public static AuthResponse ofRefresh(String access, String refresh, long expiresInMs) {
        return AuthResponse.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .tokenType("Bearer")
                .expiresIn(expiresInMs / 1000)
                .build();
    }

    /**
     * Password accepted, second factor still owed. Carries no session tokens and
     * no user payload — nothing about the account is disclosed until the second
     * factor clears.
     */
    public static AuthResponse ofMfaChallenge(String mfaToken, long ttlSeconds) {
        return AuthResponse.builder()
                .mfaRequired(true)
                .mfaToken(mfaToken)
                .expiresIn(ttlSeconds)
                .build();
    }
}
