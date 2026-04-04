package ak.dev.irc.app.security.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Utility for managing JWT cookies.
 * <p>
 * All cookie properties (name, secure, httpOnly, sameSite, path, maxAge) are
 * read from the {@link JwtTokenProvider} which loads them from {@code jwt.*}
 * in application.yml.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtCookieUtil {

    private final JwtTokenProvider jwtTokenProvider;

    // ══════════════════════════════════════════════════════════════════════════
    //  ADD JWT COOKIE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Adds a JWT access token as an HttpOnly cookie to the response.
     */
    public void addAccessTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = buildCookie(
                jwtTokenProvider.getCookieName(),
                token,
                jwtTokenProvider.getCookieMaxAge()
        );

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.debug("Access token cookie '{}' set — maxAge={}s, secure={}, httpOnly={}, sameSite='{}'",
                jwtTokenProvider.getCookieName(),
                jwtTokenProvider.getCookieMaxAge(),
                jwtTokenProvider.isCookieSecure(),
                jwtTokenProvider.isCookieHttpOnly(),
                jwtTokenProvider.getCookieSameSite());
    }

    /**
     * Adds a refresh token as an HttpOnly cookie (separate name).
     */
    public void addRefreshTokenCookie(HttpServletResponse response, String token) {
        String refreshCookieName = jwtTokenProvider.getCookieName() + "_REFRESH";

        // Refresh cookie lives longer — use refresh expiration
        int refreshMaxAge = (int) (jwtTokenProvider.getRefreshTokenExpirationMs() / 1000);

        ResponseCookie cookie = buildCookie(refreshCookieName, token, refreshMaxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        log.debug("Refresh token cookie '{}' set — maxAge={}s", refreshCookieName, refreshMaxAge);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ JWT FROM COOKIE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts the access token from the request cookies.
     */
    public Optional<String> getAccessTokenFromCookie(HttpServletRequest request) {
        return getCookieValue(request, jwtTokenProvider.getCookieName());
    }

    /**
     * Extracts the refresh token from the request cookies.
     */
    public Optional<String> getRefreshTokenFromCookie(HttpServletRequest request) {
        String refreshCookieName = jwtTokenProvider.getCookieName() + "_REFRESH";
        return getCookieValue(request, refreshCookieName);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CLEAR COOKIES (LOGOUT)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Clears both access and refresh token cookies (sets maxAge=0).
     */
    public void clearAllTokenCookies(HttpServletResponse response) {
        ResponseCookie accessClear = buildCookie(
                jwtTokenProvider.getCookieName(), "", 0);
        ResponseCookie refreshClear = buildCookie(
                jwtTokenProvider.getCookieName() + "_REFRESH", "", 0);

        response.addHeader(HttpHeaders.SET_COOKIE, accessClear.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshClear.toString());

        log.debug("All JWT cookies cleared");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ══════════════════════════════════════════════════════════════════════════

    private ResponseCookie buildCookie(String name, String value, int maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(jwtTokenProvider.isCookieHttpOnly())
                .secure(jwtTokenProvider.isCookieSecure())
                .sameSite(jwtTokenProvider.getCookieSameSite())
                .path(jwtTokenProvider.getCookiePath())
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
