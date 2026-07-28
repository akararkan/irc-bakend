package ak.dev.irc.app.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin client for the MediaMTX Control API (see {@code mediamtx.yml} → {@code
 * api: yes}). Recording is <b>per-path</b>: MediaMTX records nothing by default
 * ({@code all_others.record: no}); the backend gives each stream its own path
 * config and sets {@code record} on it, so <b>only streams asked for ever touch
 * disk</b> — no wasted writes, no delete-race orphans, no privacy window.
 *
 * <p>Lifecycle: {@link #ensurePath} on go-live (before the browser publishes),
 * {@link #pauseRecording} / {@link #resumeRecording} while live as the host
 * records in takes, {@link #removeRecordingPath} on end/delete. The split
 * matters: the path is created once, up front, and every later change patches
 * it. Adding or deleting a path config underneath a live publisher re-creates
 * the path and drops the broadcast — so only the two ends of the lifecycle may
 * do either.</p>
 *
 * <p>All calls are best-effort — a media-server hiccup must never fail the
 * control-plane action, so every call swallows errors and just logs. A failed
 * {@code ensurePath} degrades to "no recording" (the manifest will show {@code
 * EMPTY}), never a broken go-live.</p>
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

    private final ObjectMapper mapper = new ObjectMapper();

    /** Base URL of the MediaMTX Control API, e.g. {@code http://localhost:9997}. */
    @Value("${app.streaming.control-api-base:http://localhost:9997}")
    private String apiBase;

    /**
     * Create this stream's path config before anyone publishes, with {@code
     * record} set to the host's opening choice.
     *
     * <p>Called for every stream, recording or not, so that later changes are
     * always a PATCH of an existing entry. That matters because add and delete
     * both re-create the path — underneath a live publisher that ends the
     * broadcast. {@code record: false} still writes nothing to disk, so an
     * opted-out stream stays opted out.</p>
     */
    public void ensurePath(UUID streamId, boolean record) {
        String name = streamId.toString();
        String flag = record ? "true" : "false";
        int status = send("POST", "/v3/config/paths/add/" + name,
                "{\"source\":\"publisher\",\"record\":" + flag + "}");
        if (status >= 400) { // already present (retry, or MediaMTX auto-created it)
            send("PATCH", "/v3/config/paths/patch/" + name, "{\"record\":" + flag + "}");
        }
    }

    /**
     * Pause recording WITHOUT touching the publish session.
     *
     * This is deliberately a PATCH of the {@code record} flag and not
     * {@link #removeRecordingPath}: deleting the path config drops the path the
     * browser is actively publishing to, which would end the broadcast rather
     * than pause its recording. Patching leaves the path — and the live
     * viewers — completely alone.
     *
     * MediaMTX closes the current segment when the flag goes false and opens a
     * fresh one when it goes true again, so an on/off/on broadcast leaves
     * several parts on disk; they are joined into one file when the stream ends
     * (see the concat step in {@code RecordingStorageService}).
     */
    public void pauseRecording(UUID streamId) {
        send("PATCH", "/v3/config/paths/patch/" + streamId, "{\"record\":false}");
    }

    /** Resume after {@link #pauseRecording}. A pure PATCH for the same reason
     *  the pause is: {@link #ensurePath} already created the entry at go-live,
     *  so there is never a need to add one under a live publisher. */
    public void resumeRecording(UUID streamId) {
        send("PATCH", "/v3/config/paths/patch/" + streamId, "{\"record\":true}");
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

    /**
     * Hard-drop whoever is publishing to {@code path} right now — used when the
     * host takes a guest off the stage. Setting the guest {@code REMOVED} already
     * denies any <b>new</b> publish at the auth hook and the roster broadcast makes
     * every honest client drop the tile instantly; this closes the loop on a client
     * that keeps its WebRTC session open regardless (a guest who won't leave).
     *
     * <p>Best-effort and non-blocking on the result: it looks the guest's live
     * WebRTC session up by path and kicks it. A missing session (already gone) or a
     * media-server hiccup is fine — the row is already {@code REMOVED}. Guests
     * publish from the browser, so only WebRTC sessions are considered.</p>
     */
    public void kickPublisher(String path) {
        if (!StringUtils.hasText(path)) return;
        try {
            String body = sendForBody("GET", "/v3/webrtcsessions/list");
            if (body == null) return;
            JsonNode items = mapper.readTree(body).path("items");
            if (!items.isArray()) return;
            for (JsonNode item : items) {
                // Only the PUBLISHER — not the WHEP readers watching this guest, who
                // share the same path (MediaMTX marks sessions state=publish|read).
                boolean publishing = "publish".equals(item.path("state").asText(null));
                if (publishing && path.equals(item.path("path").asText(null))) {
                    String id = item.path("id").asText(null);
                    if (StringUtils.hasText(id)) send("POST", "/v3/webrtcsessions/kick/" + id, null);
                }
            }
        } catch (Exception e) {
            log.warn("[MEDIA-API] kickPublisher({}) failed: {}", path, e.getMessage());
        }
    }

    /** GET/POST that returns the response body (or null). Best-effort like
     *  {@link #send} — never throws. Used by {@link #kickPublisher}. */
    private String sendForBody(String method, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() < 400 ? res.body() : null;
        } catch (Exception e) {
            log.warn("[MEDIA-API] {} {} failed: {}", method, path, e.getMessage());
            return null;
        }
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
