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
}
