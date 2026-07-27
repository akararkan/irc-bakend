package ak.dev.irc.app.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin client for the MediaMTX Control API (see {@code mediamtx.yml} → {@code
 * api: yes}). Recording is <b>per-path opt-in</b>: MediaMTX records nothing by
 * default ({@code all_others.record: no}); when a host goes live <em>with</em>
 * recording, the backend adds a path config for exactly that stream id with
 * {@code record: true}, so <b>only opted-in streams ever touch disk</b> — no
 * wasted writes, no delete-race orphans, no privacy window.
 *
 * <p>Lifecycle: {@link #enableRecording} on go-live (before the browser
 * publishes), {@link #removeRecordingPath} on end/delete (stops recording and
 * keeps MediaMTX's runtime config bounded). Both are best-effort — a media-server
 * hiccup must never fail the control-plane action, so every call swallows errors
 * and just logs. A failed {@code enableRecording} degrades to "no recording"
 * (the manifest will show {@code EMPTY}), never a broken go-live.</p>
 *
 * <p>Uses the JDK {@link HttpClient} (no new dependency). The API port is trusted
 * on the network (localhost in dev, firewalled in prod) — it is excluded from the
 * media-auth hook in {@code mediamtx.yml} ({@code authHTTPExclude: action: api}).</p>
 */
@Slf4j
@Service
public class MediaControlClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Base URL of the MediaMTX Control API, e.g. {@code http://localhost:9997}. */
    @Value("${app.streaming.control-api-base:http://localhost:9997}")
    private String apiBase;

    /**
     * Turn on recording for a single stream's path. Adds a per-stream path config
     * ({@code source: publisher, record: true}); if it already exists (a retry, or
     * MediaMTX already auto-created the path on an early publish), patches it on.
     * The path name is the stream id (a UUID — URL-safe, no encoding needed).
     */
    public void enableRecording(UUID streamId) {
        String name = streamId.toString();
        int status = send("POST", "/v3/config/paths/add/" + name,
                "{\"source\":\"publisher\",\"record\":true}");
        if (status >= 400) { // already present / rejected → patch the record flag on
            send("PATCH", "/v3/config/paths/patch/" + name, "{\"record\":true}");
        }
    }

    /**
     * Remove the per-stream path config (stops recording, frees the config entry).
     * Idempotent — a missing path just returns 404, which we ignore. Recorded
     * files on disk are untouched (they stay downloadable until the owner deletes
     * the recording).
     */
    public void removeRecordingPath(UUID streamId) {
        send("DELETE", "/v3/config/paths/delete/" + streamId, null);
    }

    /** One API call. Returns the HTTP status, or {@code -1} if it never completed.
     *  Never throws — recording control must not break go-live / end / delete. */
    private int send(String method, String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                log.debug("[MEDIA-API] {} {} → {} {}", method, path, res.statusCode(), res.body());
            }
            return res.statusCode();
        } catch (Exception e) {
            log.warn("[MEDIA-API] {} {} failed: {}", method, path, e.getMessage());
            return -1;
        }
    }
}
