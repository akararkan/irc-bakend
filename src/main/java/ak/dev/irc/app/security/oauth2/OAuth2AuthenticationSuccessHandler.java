package ak.dev.irc.app.security.oauth2;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.security.jwt.JwtCookieUtil;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.entity.RefreshToken;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Handles successful OAuth2 authentication:
 * <ol>
 *   <li>Loads our User entity by email from the OAuth2 principal</li>
 *   <li>Generates access + refresh JWT tokens</li>
 *   <li>Sets both as HttpOnly cookies</li>
 *   <li>Persists the refresh token in the database</li>
 *   <li>Redirects to the frontend callback URL with a success indicator</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider   jwtTokenProvider;
    private final JwtCookieUtil      jwtCookieUtil;
    private final UserRepository     userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final HttpCookieOAuth2AuthorizationRequestRepository authRequestRepository;

    @Value("${app.oauth2.redirect-uri}")
    private String defaultRedirectUri;

    @Value("${app.oauth2.authorized-redirect-uris}")
    private List<String> authorizedRedirectUris;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        String targetUrl = determineTargetUrl(request, response, authentication);

        if (response.isCommitted()) {
            log.warn("Response already committed — cannot redirect to {}", targetUrl);
            return;
        }

        // Clean up OAuth2 cookies
        authRequestRepository.removeAuthorizationRequestCookies(request, response);

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {

        // ── 1. Determine redirect URI ──
        String redirectUri = getRedirectUriFromCookie(request)
                .orElse(defaultRedirectUri);

        if (!isAuthorizedRedirectUri(redirectUri)) {
            log.warn("OAuth2 redirect URI '{}' is not authorized — using default", redirectUri);
            redirectUri = defaultRedirectUri;
        }

        // ── 2. Load our User entity by email ──
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        if (email == null) {
            log.error("OAuth2 success but no email in principal — cannot issue tokens");
            return UriComponentsBuilder.fromUriString(redirectUri)
                    .queryParam("error", "email_not_found")
                    .build().toUriString();
        }

        Optional<User> optUser = userRepository.findByEmail(email);
        if (optUser.isEmpty()) {
            log.error("OAuth2 success but user not found in DB for email '{}'", email);
            return UriComponentsBuilder.fromUriString(redirectUri)
                    .queryParam("error", "user_not_found")
                    .build().toUriString();
        }

        User user = optUser.get();

        // ── 3. Generate tokens ──
        String accessToken  = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        // ── 4. Set cookies ──
        jwtCookieUtil.addAccessTokenCookie(response, accessToken);
        jwtCookieUtil.addRefreshTokenCookie(response, refreshToken);

        // ── 5. Persist refresh token ──
        persistRefreshToken(user, refreshToken);

        log.info("OAuth2 login successful — user=[{}] ({}), redirecting to '{}'",
                user.getId(), user.getEmail(), redirectUri);

        // ── 6. Build redirect URL with success indicator ──
        return UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("oauth2", "success")
                .build().toUriString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Optional<String> getRedirectUriFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();

        for (Cookie cookie : cookies) {
            if (HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE
                    .equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private boolean isAuthorizedRedirectUri(String uri) {
        return authorizedRedirectUris.stream()
                .anyMatch(authorizedUri -> {
                    // Allow exact match or same origin
                    if (uri.equals(authorizedUri)) return true;
                    try {
                        java.net.URI redirectUri = java.net.URI.create(uri);
                        java.net.URI authorised  = java.net.URI.create(authorizedUri);
                        return redirectUri.getHost().equalsIgnoreCase(authorised.getHost())
                                && redirectUri.getPort() == authorised.getPort();
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    private void persistRefreshToken(User user, String rawToken) {
        long refreshMs = jwtTokenProvider.getRefreshTokenExpirationMs();

        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .token(rawToken)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshMs / 1000))
                .build();
        entity.audit(AuditAction.CREATE, "Refresh token created (OAuth2)");
        refreshTokenRepository.save(entity);

        log.debug("Refresh token persisted for OAuth2 user [{}]", user.getId());
    }
}
