package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read-time CDN URL shaping. Rendition URLs are persisted proxy-relative
 * ({@code /api/v1/media/{key}}) — deliberately portable — so pointing clients
 * at a CDN is a config flip here, applying to historical rows too, instead of
 * a data migration.
 *
 * <p>{@code media.serving.cdn-base} empty (default) → URLs pass through
 * untouched, exactly today's behavior. With a base set:</p>
 * <ul>
 *   <li>{@code PROXY} mode — the CDN's origin is this app; the path survives:
 *       {@code https://cdn.x.com/api/v1/media/media/{id}/720p.mp4}</li>
 *   <li>{@code BUCKET} mode — the CDN fronts the bucket's custom domain; the
 *       app hop disappears: {@code https://cdn.x.com/media/{id}/720p.mp4}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MediaCdnRewriter {

    private static final String PROXY_PREFIX = "/api/v1/media/";

    private final MediaProperties props;

    /** Whether a CDN base is configured (cheap; callers may skip map copies). */
    public boolean active() {
        String base = props.getServing().getCdnBase();
        return base != null && !base.isBlank();
    }

    /** Rewrite one URL; pass-through for null, absolute, or foreign paths. */
    public String rewrite(String url) {
        if (!active() || url == null || !url.startsWith(PROXY_PREFIX)) return url;
        String base = trimTrailingSlash(props.getServing().getCdnBase());
        if ("BUCKET".equalsIgnoreCase(props.getServing().getCdnMode())) {
            return base + "/" + url.substring(PROXY_PREFIX.length());
        }
        return base + url;   // PROXY mode keeps the app path
    }

    private static String trimTrailingSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
