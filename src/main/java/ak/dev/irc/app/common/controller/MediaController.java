package ak.dev.irc.app.common.controller;

import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.research.service.S3StorageService.S3ObjectStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            Map.entry("pdf",  "application/pdf")
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

        log.debug("Proxying media request for key: {}", s3Key);

        S3ObjectStream obj = storageService.getObject(s3Key);

        // Determine content type from S3 metadata or file extension
        String contentType = obj.contentType();
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            contentType = guessContentType(s3Key);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        if (obj.contentLength() > 0) {
            headers.setContentLength(obj.contentLength());
        }
        // Allow cross-origin access for any embedded usage
        headers.set("Access-Control-Allow-Origin", "*");

        return ResponseEntity.ok()
                .headers(headers)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(new InputStreamResource(obj.inputStream()));
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

