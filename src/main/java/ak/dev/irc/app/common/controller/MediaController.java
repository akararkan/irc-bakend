package ak.dev.irc.app.common.controller;

import ak.dev.irc.app.media.service.MediaSignedUrlService;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.research.service.S3StorageService.S3ObjectStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Proxies media files from Cloudflare R2 storage to the client.
 * This eliminates CORS issues since media is served from the same origin as the API.
 *
 * URL pattern: GET /api/v1/media/{s3-key-path}
 * e.g. GET /api/v1/media/posts/media/abc123.jpg
 */
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
@Slf4j
public class MediaController {

    private final S3StorageService storageService;
    private final MediaSignedUrlService signedUrls;
    private final ak.dev.irc.app.media.repository.MediaRenditionRepository renditionRepository;

    private static final Map<String, String> EXT_TO_MIME = Map.ofEntries(
            Map.entry("jpg",  "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png",  "image/png"),
            Map.entry("gif",  "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg",  "image/svg+xml"),
            Map.entry("mp4",  "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov",  "video/quicktime"),
            Map.entry("avi",  "video/x-msvideo"),
            Map.entry("mkv",  "video/x-matroska"),
            Map.entry("mp3",  "audio/mpeg"),
            Map.entry("ogg",  "audio/ogg"),
            Map.entry("wav",  "audio/wav"),
            Map.entry("aac",  "audio/aac"),
            Map.entry("m4a",  "audio/mp4"),
            Map.entry("flac", "audio/flac"),
            Map.entry("pdf",  "application/pdf"),
            // HLS delivery (VOD packaging)
            Map.entry("m3u8", "application/vnd.apple.mpegurl"),
            Map.entry("m4s",  "video/iso.segment"),
            Map.entry("ts",   "video/mp2t")
    );

    @GetMapping("/**")
    public ResponseEntity<InputStreamResource> serveMedia(HttpServletRequest request) {
        // Extract the S3 key from the request URI (everything after /api/v1/media/)
        String fullPath = request.getRequestURI();
        String s3Key = fullPath.substring("/api/v1/media/".length());

        if (s3Key.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Prevent path traversal attacks
        if (s3Key.contains("..")) {
            return ResponseEntity.badRequest().build();
        }

        // Pre-moderation originals (intent-flow staging area) are never publicly
        // addressable — only produced renditions under media/ serve.
        if (s3Key.startsWith("raw/")) {
            return ResponseEntity.notFound().build();
        }

        // Signed-URL enforcement is prefix-scoped and off by default — public
        // keys never reach this check, so historical URLs keep working.
        if (signedUrls.protects(s3Key)
                && !signedUrls.verify(s3Key, request.getParameter("exp"), request.getParameter("sig"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.debug("Proxying media request for key: {}", s3Key);

        // Forward any HTTP Range so <video>/<audio> can seek, and If-None-Match
        // so an unchanged object costs a 304 instead of a transfer. R2 returns
        // the partial body + a Content-Range; absent a Range we serve the full
        // object but still advertise Accept-Ranges so the browser knows it may seek.
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        S3ObjectStream obj;
        try {
            obj = storageService.getObject(s3Key, rangeHeader, ifNoneMatch);
        } catch (S3StorageService.NotModifiedException notModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(notModified.etag())
                    .cacheControl(cachePolicyFor(s3Key))
                    .build();
        } catch (ak.dev.irc.app.common.exception.AppException gone) {
            // Stale-original healing: chat messages and legacy rows persist
            // /media/{id}/original.* verbatim, and the purge job may have
            // reclaimed that object — answer with the best surviving rendition
            // instead of a 404 so old links keep playing.
            if (gone.getStatus() == HttpStatus.NOT_FOUND) {
                ResponseEntity<InputStreamResource> fallback = redirectForMissingOriginal(s3Key);
                if (fallback != null) return fallback;
            }
            throw gone;
        }

        // Determine content type from S3 metadata or file extension
        String contentType = obj.contentType();
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            contentType = guessContentType(s3Key);
        }

        boolean partial = obj.contentRange() != null && !obj.contentRange().isBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (obj.contentLength() > 0) {
            headers.setContentLength(obj.contentLength());   // length of THIS body (partial or full)
        }
        if (partial) {
            headers.set(HttpHeaders.CONTENT_RANGE, obj.contentRange());
        }
        if (obj.etag() != null && !obj.etag().isBlank()) {
            headers.setETag(obj.etag());
        }
        // NOTE: CORS headers are emitted by the Spring Security CORS filter — do
        // NOT set Access-Control-Allow-Origin here (it produced a duplicate ACAO).

        return ResponseEntity
                .status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .headers(headers)
                .cacheControl(cachePolicyFor(s3Key))
                .body(new InputStreamResource(obj.inputStream()));
    }

    /**
     * Cheap HEAD without Spring's default GET-and-discard (which would stream
     * the whole object out of storage just to drop it): a 1-byte ranged read
     * yields size, type and ETag, and the response carries no body.
     */
    @RequestMapping(value = "/**", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headMedia(HttpServletRequest request) {
        String s3Key = request.getRequestURI().substring("/api/v1/media/".length());
        if (s3Key.isBlank() || s3Key.contains("..")) return ResponseEntity.badRequest().build();
        if (s3Key.startsWith("raw/")) return ResponseEntity.notFound().build();
        if (signedUrls.protects(s3Key)
                && !signedUrls.verify(s3Key, request.getParameter("exp"), request.getParameter("sig"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        S3ObjectStream obj;
        try {
            obj = storageService.getObject(s3Key, "bytes=0-0");
            obj.inputStream().close();
        } catch (Exception e) {
            ResponseEntity<InputStreamResource> fallback = redirectForMissingOriginal(s3Key);
            if (fallback != null) {
                return ResponseEntity.status(fallback.getStatusCode())
                        .headers(fallback.getHeaders()).build();
            }
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        String contentType = obj.contentType();
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            contentType = guessContentType(s3Key);
        }
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        Long total = obj.totalLength();
        if (total != null && total > 0) {
            headers.setContentLength(total);
        } else if (obj.contentRange() == null && obj.contentLength() > 0) {
            headers.setContentLength(obj.contentLength());   // backend ignored the range
        }
        if (obj.etag() != null && !obj.etag().isBlank()) headers.setETag(obj.etag());
        return ResponseEntity.ok().headers(headers).cacheControl(cachePolicyFor(s3Key)).build();
    }

    /**
     * When a {@code media/{assetId}/original.*} object is gone (reclaimed by
     * the ladder-original purge, or removed out-of-band) but the asset still
     * has renditions, answer 302 to the best surviving one. Chat messages and
     * legacy rows persist the original URL verbatim, so this keeps every old
     * link playable. Only fires for the {@code original} label — anything
     * else missing is a genuine 404. Redirect is uncacheable so a later state
     * change is never pinned.
     */
    private ResponseEntity<InputStreamResource> redirectForMissingOriginal(String s3Key) {
        if (!s3Key.startsWith("media/")) return null;
        String[] parts = s3Key.split("/");
        if (parts.length != 3 || !parts[2].startsWith("original")) return null;
        java.util.UUID assetId;
        try {
            assetId = java.util.UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            var renditions = renditionRepository.findByIdMediaId(assetId);
            Map<String, String> variants =
                    ak.dev.irc.app.media.dto.MediaVariants.toClientMap(renditions);
            variants.remove("original");   // the thing that's missing
            String target = ak.dev.irc.app.media.dto.MediaVariants.bestVideoUrl(variants);
            if (target == null) target = ak.dev.irc.app.media.dto.MediaVariants.primaryUrl(variants);
            if (target == null || target.isBlank()) return null;
            log.info("[MEDIA] original for {} gone — redirecting to surviving rendition", assetId);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.LOCATION, target);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .headers(headers)
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (Exception ex) {
            log.warn("[MEDIA] original-fallback lookup failed for {}: {}", assetId, ex.getMessage());
            return null;
        }
    }

    /**
     * Rendition keys ({@code media/{assetId}/{label}.…}) are content-addressed
     * and never rewritten — a year + immutable. Legacy prefixes keep the 7-day
     * policy (UUID-named, practically immutable, but no hard guarantee).
     * Signature-protected keys must never park in shared caches.
     */
    private CacheControl cachePolicyFor(String s3Key) {
        if (signedUrls.protects(s3Key)) {
            return CacheControl.noStore().cachePrivate();
        }
        if (s3Key.startsWith("media/")) {
            return CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
        }
        return CacheControl.maxAge(Duration.ofDays(7)).cachePublic();
    }

    private String guessContentType(String key) {
        int dotIndex = key.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < key.length() - 1) {
            String ext = key.substring(dotIndex + 1).toLowerCase();
            return EXT_TO_MIME.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }
}

