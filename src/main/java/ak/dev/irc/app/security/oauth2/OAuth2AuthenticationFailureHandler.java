package ak.dev.irc.app.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles OAuth2 authentication failure by:
 * <ol>
 *   <li>Cleaning up OAuth2 cookies</li>
 *   <li>Logging the failure details</li>
 *   <li>Redirecting to the frontend with an error query parameter</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final HttpCookieOAuth2AuthorizationRequestRepository authRequestRepository;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {

        log.error("OAuth2 authentication failed — {}: {}",
                exception.getClass().getSimpleName(), exception.getMessage());

        // Clean up OAuth2 cookies
        authRequestRepository.removeAuthorizationRequestCookies(request, response);

        String encodedError = URLEncoder.encode(
                exception.getLocalizedMessage(), StandardCharsets.UTF_8);

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", encodedError)
                .build().toUriString();

        log.debug("Redirecting to '{}' after OAuth2 failure", targetUrl);

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
