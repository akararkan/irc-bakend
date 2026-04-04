package ak.dev.irc.app.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import java.util.Base64;

/**
 * Stores the OAuth2 authorization request in a short-lived cookie so the
 * stateless API doesn't need server-side sessions.
 * <p>
 * Also stores the {@code redirect_uri} query parameter so the success handler
 * knows where to send the user after authentication.
 * </p>
 */
@Slf4j
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String OAUTH2_AUTH_REQUEST_COOKIE   = "oauth2_auth_request";
    public static final String REDIRECT_URI_PARAM_COOKIE    = "redirect_uri";
    private static final int   COOKIE_EXPIRE_SECONDS        = 180; // 3 minutes

    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookieValue(request, OAUTH2_AUTH_REQUEST_COOKIE);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (authorizationRequest == null) {
            deleteCookies(request, response);
            return;
        }

        String serialized = Base64.getUrlEncoder().encodeToString(
                SerializationUtils.serialize(authorizationRequest));

        addCookie(response, OAUTH2_AUTH_REQUEST_COOKIE, serialized, COOKIE_EXPIRE_SECONDS);

        String redirectUri = request.getParameter(REDIRECT_URI_PARAM_COOKIE);
        if (redirectUri != null && !redirectUri.isBlank()) {
            addCookie(response, REDIRECT_URI_PARAM_COOKIE, redirectUri, COOKIE_EXPIRE_SECONDS);
        }

        log.debug("OAuth2 authorization request saved to cookie");
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {

        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        if (authRequest != null) {
            log.debug("OAuth2 authorization request removed from cookie");
        }
        return authRequest;
    }

    /**
     * Called after OAuth2 flow completes (success or failure) to clean up cookies.
     */
    public void removeAuthorizationRequestCookies(
            HttpServletRequest request, HttpServletResponse response) {
        deleteCookies(request, response);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COOKIE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private OAuth2AuthorizationRequest getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(cookie.getValue());
                    return (OAuth2AuthorizationRequest) SerializationUtils.deserialize(decoded);
                } catch (Exception ex) {
                    log.warn("Failed to deserialize OAuth2 cookie '{}' — {}", name, ex.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        response.addCookie(cookie);
    }

    private void deleteCookies(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return;

        for (Cookie cookie : cookies) {
            if (OAUTH2_AUTH_REQUEST_COOKIE.equals(cookie.getName())
                    || REDIRECT_URI_PARAM_COOKIE.equals(cookie.getName())) {
                Cookie cleared = new Cookie(cookie.getName(), "");
                cleared.setPath("/");
                cleared.setHttpOnly(true);
                cleared.setMaxAge(0);
                response.addCookie(cleared);
            }
        }
    }
}
