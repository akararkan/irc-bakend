package ak.dev.irc.app.share;

import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostMedia;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import java.util.Optional;
import java.util.UUID;

/**
 * Public share-link redirect endpoints.
 *
 * <p>The backend is the canonical share host so links survive frontend domain
 * changes and proxies. Each endpoint resolves the entity, renders an HTML
 * page with Open Graph + Twitter Card metadata so social platforms (Slack,
 * Twitter, LinkedIn, WhatsApp, Discord) generate a rich preview, then
 * redirects the visitor to the frontend.</p>
 *
 * <ul>
 *   <li>{@code GET /p/{token}} — post (token = 12-char shareLink or post UUID)</li>
 *   <li>{@code GET /r/{token}} — research (16-char shareToken)</li>
 *   <li>{@code GET /q/{questionId}} — question (UUID)</li>
 * </ul>
 *
 * <p>If the entity is missing, returns a 404 page that still redirects the
 * visitor to the frontend home so a stale link does not look broken.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class SharePreviewController {

    private final PostRepository     postRepository;
    private final ResearchRepository researchRepository;
    private final QuestionRepository questionRepository;

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Value("${irc.base-url:http://localhost:8080}")
    private String backendBaseUrl;

    @Value("${irc.email.from-name:IRC Platform}")
    private String brandName;

    // ── /p/{token} ───────────────────────────────────────────────────────

    @GetMapping(value = "/p/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> sharePost(@PathVariable String token,
                                            HttpServletRequest request) {
        Optional<Post> opt = resolvePost(token);
        if (opt.isEmpty()) {
            log.info("[Share] post token not found: {}", token);
            return notFound("Post", frontendUrl(request) + "/feed");
        }
        Post post = opt.get();
        String redirectTo = frontendUrl(request) + "/posts/" + post.getId();
        String title = excerpt(firstLine(post.getTextContent()), 90);
        if (title.isEmpty()) title = "A post on " + brandName;
        String description = excerpt(post.getTextContent(), 220);
        String image = pickPostImage(post);

        return ok(renderPreview(title, description, image, redirectTo, request));
    }

    // ── /r/{token} ───────────────────────────────────────────────────────

    @GetMapping(value = "/r/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> shareResearch(@PathVariable String token,
                                                HttpServletRequest request) {
        Optional<Research> opt = researchRepository.findByShareTokenAndDeletedAtIsNull(token);
        if (opt.isEmpty()) {
            log.info("[Share] research token not found: {}", token);
            return notFound("Research", frontendUrl(request) + "/research");
        }
        Research r = opt.get();
        String redirectTo = frontendUrl(request) + "/research/" + r.getId();
        String description = excerpt(safeGet(r::getDescription), 220);
        String image = safeGet(r::getCoverImageUrl);

        return ok(renderPreview(r.getTitle(), description, image, redirectTo, request));
    }

    // ── /q/{questionId} ──────────────────────────────────────────────────

    @GetMapping(value = "/q/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> shareQuestion(@PathVariable String id,
                                                HttpServletRequest request) {
        UUID qid;
        try { qid = UUID.fromString(id); }
        catch (IllegalArgumentException ex) {
            return notFound("Question", frontendUrl(request) + "/questions");
        }
        Optional<Question> opt = questionRepository.findById(qid)
                .filter(q -> q.getDeletedAt() == null);
        if (opt.isEmpty()) {
            log.info("[Share] question not found: {}", id);
            return notFound("Question", frontendUrl(request) + "/questions");
        }
        Question q = opt.get();
        String redirectTo = frontendUrl(request) + "/questions/" + q.getId();
        String description = excerpt(q.getBody(), 220);

        return ok(renderPreview(q.getTitle(), description, null, redirectTo, request));
    }

    // ── Resolution helpers ───────────────────────────────────────────────

    private Optional<Post> resolvePost(String token) {
        Optional<Post> byToken = postRepository.findByShareLink(token);
        if (byToken.isPresent()) return byToken;
        try {
            return postRepository.findById(UUID.fromString(token));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Picks the OG image: first the post's media attachments (image preferred,
     * then video thumbnail), then the author's avatar. Must be called inside the
     * @Transactional method so LAZY collections / relations can be initialised.
     */
    private String pickPostImage(Post post) {
        try {
            List<PostMedia> media = post.getMediaList();
            if (media != null && !media.isEmpty()) {
                for (PostMedia m : media) {
                    if (m.getUrl() != null && !m.getUrl().isBlank()) return m.getUrl();
                    if (m.getThumbnailUrl() != null && !m.getThumbnailUrl().isBlank())
                        return m.getThumbnailUrl();
                }
            }
        } catch (Exception ignored) { /* lazy-init guard */ }
        try {
            User author = post.getAuthor();
            return author == null ? null : author.getProfileImage();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── HTML rendering ───────────────────────────────────────────────────

    private ResponseEntity<String> ok(String html) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "public, max-age=300")
                .body(html);
    }

    private ResponseEntity<String> notFound(String kind, String fallbackUrl) {
        String html = renderPreview(
                kind + " not found",
                "This share link is invalid or has expired. You'll be redirected to " + brandName + ".",
                null,
                fallbackUrl,
                null);
        return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private String renderPreview(String title, String description, String image,
                                 String redirectTo, HttpServletRequest request) {
        String safeTitle = escape(title == null ? brandName : title);
        String safeDesc  = escape(description == null ? "" : description);
        String shareUrl  = currentUrl(request);

        String ogImage = image != null && !image.isBlank() ? """
            <meta property="og:image" content="%s"/>
            <meta name="twitter:image" content="%s"/>
            <meta name="twitter:card" content="summary_large_image"/>
            """.formatted(escape(image), escape(image))
            : "<meta name=\"twitter:card\" content=\"summary\"/>";

        String body = redirectTo == null ? "" : """
            <p style="margin:24px 0 0;">If you're not redirected automatically,
              <a href="%s" style="color:#1f6feb;text-decoration:none;font-weight:600;">click here to continue</a>.
            </p>
            """.formatted(escape(redirectTo));

        String redirectHead = redirectTo == null ? "" : """
            <meta http-equiv="refresh" content="0;url=%s"/>
            <script>window.location.replace(%s);</script>
            """.formatted(escape(redirectTo), jsString(redirectTo));

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>%s · %s</title>
              <meta name="description" content="%s"/>
              <meta property="og:title" content="%s"/>
              <meta property="og:description" content="%s"/>
              <meta property="og:url" content="%s"/>
              <meta property="og:type" content="article"/>
              <meta property="og:site_name" content="%s"/>
              <meta name="twitter:title" content="%s"/>
              <meta name="twitter:description" content="%s"/>
              %s
              %s
            </head>
            <body style="margin:0;padding:0;background:#f6f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#0d1117;">
              <div style="max-width:560px;margin:80px auto;padding:32px;background:#ffffff;
                          border:1px solid #d8dee4;border-radius:10px;text-align:center;">
                <p style="margin:0;color:#57606a;font-size:13px;letter-spacing:1px;text-transform:uppercase;">%s</p>
                <h1 style="margin:12px 0 8px;font-size:22px;line-height:1.35;">%s</h1>
                <p style="margin:0;color:#57606a;font-size:15px;line-height:1.5;">%s</p>
                %s
              </div>
            </body>
            </html>
            """.formatted(
                safeTitle, escape(brandName),
                safeDesc,
                safeTitle, safeDesc, escape(shareUrl), escape(brandName),
                safeTitle, safeDesc,
                ogImage,
                redirectHead,
                escape(brandName),
                safeTitle,
                safeDesc,
                body
        );
    }

    // ── URL + string helpers ─────────────────────────────────────────────

    /** Frontend base — falls back to the request origin if {@code app.frontend-url} is unset. */
    private String frontendUrl(HttpServletRequest request) {
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            return trimTrailingSlash(frontendUrl);
        }
        // Fallback: derive from the incoming request so a missing env var doesn't
        // produce broken redirects in dev.
        String scheme = headerOr(request, "X-Forwarded-Proto", request.getScheme());
        String host   = headerOr(request, "X-Forwarded-Host",  request.getServerName());
        return scheme + "://" + host;
    }

    private String currentUrl(HttpServletRequest request) {
        if (request == null) return trimTrailingSlash(backendBaseUrl);
        String scheme = headerOr(request, "X-Forwarded-Proto", request.getScheme());
        String host   = headerOr(request, "X-Forwarded-Host",  request.getServerName());
        return scheme + "://" + host + request.getRequestURI();
    }

    private String headerOr(HttpServletRequest request, String header, String fallback) {
        String v = request.getHeader(header);
        return v == null || v.isBlank() ? fallback : v.split(",")[0].trim();
    }

    private String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s.trim() : s.substring(0, nl).trim();
    }

    private String excerpt(String s, int max) {
        if (s == null) return "";
        String trimmed = s.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private String safeGet(java.util.function.Supplier<String> get) {
        try { return get.get(); } catch (Exception ex) { return null; }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
