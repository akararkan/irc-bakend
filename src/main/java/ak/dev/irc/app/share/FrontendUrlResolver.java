package ak.dev.irc.app.share;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the user-facing frontend URL.
 *
 * <p>Every service that mints a URL the user will click — share-link
 * endpoints, email CTAs, share preview redirects — resolves it here so we
 * never accidentally hand someone a backend API URL like
 * {@code https://api.example.com/posts/{uuid}}. The backend has no
 * user-facing pages, so those URLs always 404.</p>
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code app.frontend-url} env (FRONTEND_URL on Railway).</li>
 *   <li>The request origin — only used when the request comes from a
 *       localhost / RFC-1918 host so dev still works without env vars.
 *       Public hosts are NEVER used as a fallback.</li>
 * </ol>
 *
 * <p>If {@code app.frontend-url} is unset on a public host the resolver
 * logs an error and returns the request origin anyway — links will be
 * broken, but the operator gets a loud signal in the logs to fix the env
 * var.</p>
 */
@Slf4j
@Component
public class FrontendUrlResolver {

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    /** Resolve when no request is in scope (e.g. from an async email send). */
    public String resolve() {
        return frontendUrl == null || frontendUrl.isBlank() ? null : trim(frontendUrl);
    }

    /** Resolve in the context of an inbound request, with a localhost-only fallback. */
    public String resolve(HttpServletRequest request) {
        if (frontendUrl != null && !frontendUrl.isBlank()) return trim(frontendUrl);
        if (request == null) return null;

        String host = headerOr(request, "X-Forwarded-Host", request.getServerName());
        boolean isLocal = host != null
                && (host.startsWith("localhost") || host.startsWith("127.")
                    || host.startsWith("192.168.") || host.startsWith("10.")
                    || host.startsWith("172."));
        String scheme = headerOr(request, "X-Forwarded-Proto", request.getScheme());
        if (isLocal) {
            return scheme + "://" + host;
        }
        log.error("[Share] ❌ FRONTEND_URL is unset and request host '{}' is public — share links "
                + "and emails will point at the backend (which has no user-facing pages). Set "
                + "FRONTEND_URL on Railway to your app URL.", host);
        return scheme + "://" + host;
    }

    private static String trim(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String headerOr(HttpServletRequest request, String header, String fallback) {
        String v = request.getHeader(header);
        return v == null || v.isBlank() ? fallback : v.split(",")[0].trim();
    }
}
