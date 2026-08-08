package ak.dev.irc.app.moderation.client;

import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Java side of the {@code /v1/score} contract (MODERATION_ROADMAP.md §7.4).
 *
 * <p>Built on the JDK's {@code java.net.http.HttpClient} rather than WebClient or
 * Feign, matching {@code MediaControlClient} — the project's only other outbound
 * HTTP client — because neither spring-webflux nor Resilience4j is a dependency
 * here and the build runs offline. Timeout, bounded retry and the circuit
 * breaker the roadmap asks for are therefore hand-rolled in
 * {@link ModerationCircuitBreaker} and in {@link #post}.</p>
 *
 * <p>Failure semantics are the important part: this class throws
 * {@link InferenceUnavailableException} rather than returning a neutral score.
 * "The model is down" must never be mistaken for "the model said it's clean"
 * (§7.4) — the fallback policy in §5.6 only works if the two are distinct.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationInferenceClient {

    private static final String HEADER_API_KEY = "X-API-Key";

    private final ModerationProperties properties;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private ModerationCircuitBreaker breaker;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private volatile String lastKnownVersion = "unknown";
    private volatile String lastError;

    @PostConstruct
    void init() {
        ModerationProperties.Inference cfg = properties.getInference();
        httpClient = HttpClient.newBuilder()
                // HTTP/1.1 explicitly. The JDK client defaults to HTTP_2, which on a
                // cleartext connection first sends an h2c upgrade handshake
                // (`Connection: Upgrade, HTTP2-Settings`). uvicorn/h11 does not speak
                // h2c: it logs "Unsupported upgrade request" and the POST body is lost,
                // so FastAPI sees no `items` and answers 422 — while GET /healthz, which
                // has no body, still succeeds. The result is the worst possible symptom:
                // the service reports UP and every single score silently fails.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        breaker = new ModerationCircuitBreaker("inference", cfg.getCircuitWindow(),
                cfg.getCircuitFailureRatePercent(), cfg.getCircuitOpenMs());
        log.info("[MODERATION] inference client → {} (timeout {}ms, {} attempts)",
                cfg.getBaseUrl(), cfg.getTimeoutMs(), cfg.getMaxAttempts());
    }

    // ── scoring ─────────────────────────────────────────────────────────

    /** Single text, low-latency path — live chat (§6) and synchronous edit checks (§5.5). */
    public ScoreResult score(String text, Duration budget) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text == null ? "" : text);

        JsonNode response = post("/v1/score", body, budget);
        String version = response.path("model_version").asText("unknown");
        lastKnownVersion = version;
        return new ScoreResult("text", readScores(response.path("scores")), version,
                response.path("inference_ms").asDouble(0));
    }

    /**
     * All of an entity's fields in one round-trip (§5.4, §16). Batching matters:
     * a post with a title, body and five tags is one call instead of seven, which
     * is the difference between clearing inside the hold window and not.
     *
     * <p>Requests larger than the container's configured ceiling are split
     * client-side; the container answers 413 rather than truncating, so silently
     * dropping fields is not a failure mode here.</p>
     */
    public List<ScoreResult> scoreBatch(List<BatchItem> items, Duration budget) {
        if (items == null || items.isEmpty()) return List.of();

        int chunkSize = Math.max(1, properties.getInference().getMaxBatchItems());
        List<ScoreResult> results = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += chunkSize) {
            List<BatchItem> chunk = items.subList(start, Math.min(items.size(), start + chunkSize));
            results.addAll(scoreOneBatch(chunk, budget));
        }
        return results;
    }

    private List<ScoreResult> scoreOneBatch(List<BatchItem> items, Duration budget) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode array = body.putArray("items");
        for (BatchItem item : items) {
            ObjectNode node = array.addObject();
            node.put("id", item.id());
            node.put("text", item.text() == null ? "" : item.text());
        }

        JsonNode response = post("/v1/score/batch", body, budget);
        String version = response.path("model_version").asText("unknown");
        lastKnownVersion = version;

        List<ScoreResult> results = new ArrayList<>(items.size());
        JsonNode rows = response.path("results");
        for (JsonNode row : rows) {
            results.add(new ScoreResult(row.path("id").asText(),
                    readScores(row.path("scores")), version, 0));
        }
        // The contract is one result per item. A short answer means a container
        // version drift, not a clean field — fail loudly rather than approve.
        if (results.size() != items.size()) {
            throw new InferenceUnavailableException(
                    "batch returned %d results for %d items".formatted(results.size(), items.size()));
        }
        return results;
    }

    // ── model management (§12.4) ────────────────────────────────────────

    /**
     * Hot-swaps the resident artifact so a promotion does not need a container
     * roll. Returns the version actually loaded.
     */
    public String reload(String version) {
        ObjectNode body = objectMapper.createObjectNode();
        if (version != null && !version.isBlank()) body.put("version", version);
        JsonNode response = post("/v1/reload", body,
                Duration.ofMillis(properties.getInference().getTimeoutMs() * 4));
        String loaded = response.path("model_version").asText("unknown");
        lastKnownVersion = loaded;
        log.info("[MODERATION] inference reloaded → {}", loaded);
        return loaded;
    }

    /** Health probe for the ops board — never throws. */
    /**
     * Probe only. Deliberately excluded from the counters and from
     * {@link #lastError}: a health GET succeeding would otherwise wipe the error
     * from the last failed <em>score</em>, and the ops panel calls this every
     * time it renders. That is not hypothetical — it is exactly what masked a
     * total scoring outage behind a green "inferenceUp: true".
     */
    public Health health() {
        try {
            JsonNode response = exchange("GET", "/healthz", null,
                    Duration.ofMillis(2000), true);
            String version = response.path("model_version").asText("unknown");
            lastKnownVersion = version;
            return new Health(true, version, breaker.state(), null);
        } catch (Exception ex) {
            return new Health(false, lastKnownVersion, breaker.state(), ex.getMessage());
        }
    }

    /** Which artifact the container reports serving right now. */
    public String currentModelVersion() {
        return lastKnownVersion;
    }

    public boolean circuitOpen() {
        return breaker.open();
    }

    /** Latency/error counters for the §12.5 SLA panel. */
    public Stats stats() {
        long total = calls.get();
        long failed = failures.get();
        return new Stats(total, failed,
                total == 0 ? 0 : Math.round((double) totalLatencyMs.get() / total),
                breaker.state(), lastKnownVersion, lastError);
    }

    // ── transport ───────────────────────────────────────────────────────

    private JsonNode post(String path, ObjectNode body, Duration budget) {
        return exchange("POST", path, body, budget, false);
    }

    /**
     * @param diagnostic true for the health probe — skips the breaker, the
     *                   counters and {@code lastError} so observing the service
     *                   never alters what the service reports about itself
     */
    private JsonNode exchange(String method, String path, ObjectNode body, Duration budget,
                              boolean diagnostic) {
        if (!diagnostic && !breaker.allowRequest()) {
            throw new InferenceUnavailableException("circuit breaker open for " + path);
        }

        ModerationProperties.Inference cfg = properties.getInference();
        // The per-entity inline budget is an upper bound on the whole attempt;
        // never let a caller's generous budget exceed the client-wide ceiling.
        long timeoutMs = Math.min(
                budget == null ? cfg.getTimeoutMs() : Math.max(200, budget.toMillis()),
                cfg.getTimeoutMs());

        RuntimeException last = null;
        for (int attempt = 1; attempt <= Math.max(1, cfg.getMaxAttempts()); attempt++) {
            long started = System.currentTimeMillis();
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.getBaseUrl() + path))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json");
                if (!cfg.getApiKey().isBlank()) {
                    request.header(HEADER_API_KEY, cfg.getApiKey());
                }
                if ("GET".equals(method)) {
                    request.GET();
                } else {
                    request.POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)));
                }

                HttpResponse<String> response =
                        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());

                if (!diagnostic) {
                    calls.incrementAndGet();
                    totalLatencyMs.addAndGet(System.currentTimeMillis() - started);
                }

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    if (!diagnostic) {
                        breaker.recordSuccess();
                        lastError = null;
                    }
                    return objectMapper.readTree(response.body());
                }
                // 4xx is our bug (bad payload, wrong key) and will fail identically
                // on retry — only 5xx/503-model-loading is worth another attempt.
                String detail = "%s %s → HTTP %d: %s"
                        .formatted(method, path, response.statusCode(), truncate(response.body()));
                last = new InferenceUnavailableException(detail);
                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InferenceUnavailableException("interrupted calling " + path, ie);
            } catch (Exception ex) {
                if (!diagnostic) {
                    calls.incrementAndGet();
                    totalLatencyMs.addAndGet(System.currentTimeMillis() - started);
                }
                last = new InferenceUnavailableException(
                        "%s %s failed: %s".formatted(method, path, ex.getMessage()), ex);
            }

            if (attempt < cfg.getMaxAttempts()) {
                sleep(cfg.getRetryBackoffMs());
            }
        }

        if (!diagnostic) {
            failures.incrementAndGet();
            breaker.recordFailure();
            lastError = last == null ? "unknown" : last.getMessage();
            // WARN, not DEBUG. A scoring call that fails is content not being
            // checked — the previous debug level meant a total outage produced
            // no visible log line at default settings.
            log.warn("[MODERATION] inference call failed: {}", lastError);
        }
        throw last == null ? new InferenceUnavailableException("no response from " + path) : last;
    }

    private Map<ModerationLabel, Double> readScores(JsonNode node) {
        Map<ModerationLabel, Double> scores = new EnumMap<>(ModerationLabel.class);
        if (node == null || !node.isObject()) return scores;
        node.fields().forEachRemaining(entry ->
                ModerationLabel.fromWire(entry.getKey())
                        .ifPresent(label -> scores.put(label, entry.getValue().asDouble(0))));
        return scores;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200);
    }

    /** One field of a batch scoring request. */
    public record BatchItem(String id, String text) {
    }

    public record Health(boolean up, String modelVersion, String circuit, String error) {
    }

    public record Stats(long calls, long failures, long avgLatencyMs,
                        String circuit, String modelVersion, String lastError) {
    }
}
