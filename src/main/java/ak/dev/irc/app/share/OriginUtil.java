package ak.dev.irc.app.share;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds a canonical origin string ({@code scheme://host[:port]}) for the
 * incoming request, honouring {@code X-Forwarded-*} headers so URLs minted
 * behind Railway / Cloudflare carry the public hostname instead of the
 * internal container address. Used by every share-link endpoint so the URL
 * the user sees in the response is the URL their browser will actually open.
 */
public final class OriginUtil {

    private OriginUtil() { }

    public static String origin(HttpServletRequest request) {
        if (request == null) return null;
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost  = request.getHeader("X-Forwarded-Host");
        String scheme = (forwardedProto != null && !forwardedProto.isBlank())
                ? forwardedProto.split(",")[0].trim()
                : request.getScheme();
        String host = (forwardedHost != null && !forwardedHost.isBlank())
                ? forwardedHost.split(",")[0].trim()
                : request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443)
                || host.contains(":");
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
