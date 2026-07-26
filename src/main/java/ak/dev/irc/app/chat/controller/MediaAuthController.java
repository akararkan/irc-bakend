package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.MediaAuthRequest;
import ak.dev.irc.app.chat.service.LiveStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Media-server auth hook. The media server (MediaMTX) POSTs here for every
 * publish/read; a {@code 20x} authorizes it, anything else denies. This is the
 * enforcement point that keeps the ingest host-only: a publish must present the
 * stream's secret {@code streamKey}, while reads (HLS playback) are public for
 * LIVE streams. Deliberately unauthenticated (the media server has no JWT) — the
 * {@code {secret}} path segment, a shared secret with {@code mediamtx.yml}, is
 * what keeps the endpoint callable only by our media server.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MediaAuthController {

    private final LiveStreamService liveStreamService;

    @Value("${app.streaming.auth-secret:dev-media-auth-secret}")
    private String authSecret;

    @PostMapping("/internal/media/auth/{secret}")
    public ResponseEntity<Void> authorize(@PathVariable String secret,
                                          @RequestBody MediaAuthRequest req) {
        if (!constantTimeEquals(secret, authSecret)) {
            log.warn("Media-auth: rejected call with bad shared secret (action={}, path={})",
                    req.getAction(), req.getPath());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean ok = liveStreamService.authorizeMediaAccess(
                req.getAction(), req.getPath(), req.getPassword(), req.getQuery());
        return ok ? ResponseEntity.ok().build()
                  : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                                     b.getBytes(StandardCharsets.UTF_8));
    }
}
