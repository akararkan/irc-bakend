package ak.dev.irc.app.moderation.image;

import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.client.InferenceUnavailableException;
import ak.dev.irc.app.moderation.client.ModerationCircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Java side of the image scorer's {@code /v1/score} contract
 * (docs/model-image-inference). A deliberate twin of
 * {@code ModerationInferenceClient}: same JDK {@code HttpClient} transport
 * (HTTP/1.1 pinned — see that class for the h2c/uvicorn trap), same hand-rolled
 * retry + {@link ModerationCircuitBreaker}, same failure semantics.
 *
 * <p>One semantic this client adds: a 4xx from the scorer (bad base64, corrupt
 * bytes, decompression bomb) throws {@link ImageUnscorableException} and does
 * <em>not</em> count as a breaker failure. "The user uploaded a broken file"
 * and "the model is down" demand opposite reactions — letting the former trip
 * the breaker would turn one hostile client into a scoring outage for
 * everyone.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageModerationClient {

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
        ModerationProperties.Image cfg = properties.getImage();
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        breaker = new ModerationCircuitBreaker("image-inference", cfg.getCircuitWindow(),
                cfg.getCircuitFailureRatePercent(), cfg.getCircuitOpenMs());
        log.info("[MODERATION] image inference client → {} (timeout {}ms, {} attempts)",
                cfg.getBaseUrl(), cfg.getTimeoutMs(), cfg.getMaxAttempts());
    }

    // ── scoring ─────────────────────────────────────────────────────────

    /**
     * Scores one image. Throws {@link ImageUnscorableException} for input the
     * scorer cannot decode, {@link InferenceUnavailableException} when the
     * scorer is down/slow — callers must treat those differently.
     */
    public ImageScoreResult score(byte[] imageBytes, Duration budget) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("image_b64", Base64.getEncoder().encodeToString(
                imageBytes == null ? new byte[0] : imageBytes));

        JsonNode response = post("/v1/score", body, budget);
        String version = response.path("model_version").asText("unknown");
        lastKnownVersion = version;
        JsonNode scores = response.path("scores");
        return new ImageScoreResult(
                scores.path("nsfw").asDouble(0),
                scores.path("normal").asDouble(0),
                version,
                response.path("inference_ms").asDouble(0));
    }

    /** Health probe for the ops board — never throws, never touches the counters. */
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

    public boolean circuitOpen() {
        return breaker.open();
    }

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

    private JsonNode exchange(String method, String path, ObjectNode body, Duration budget,
                              boolean diagnostic) {
        if (!diagnostic && !breaker.allowRequest()) {
            throw new InferenceUnavailableException("image circuit breaker open for " + path);
        }

        ModerationProperties.Image cfg = properties.getImage();
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

                // 400/413/415/422: OUR payload is undecodable — the file, not the
                // service. Success for the breaker (the scorer answered), a
                // distinct exception for the gate. 401 stays an availability
                // problem: a wrong API key means nothing is being screened.
                if (!diagnostic && response.statusCode() >= 400 && response.statusCode() < 500
                        && response.statusCode() != 401 && response.statusCode() != 429) {
                    breaker.recordSuccess();
                    throw new ImageUnscorableException("%s → HTTP %d: %s".formatted(
                            path, response.statusCode(), truncate(response.body())));
                }

                String detail = "%s %s → HTTP %d: %s"
                        .formatted(method, path, response.statusCode(), truncate(response.body()));
                last = new InferenceUnavailableException(detail);
                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    break;
                }
            } catch (ImageUnscorableException unscorable) {
                throw unscorable;
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
            log.warn("[MODERATION] image inference call failed: {}", lastError);
        }
        throw last == null ? new InferenceUnavailableException("no response from " + path) : last;
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

    public record Health(boolean up, String modelVersion, String circuit, String error) {
    }

    public record Stats(long calls, long failures, long avgLatencyMs,
                        String circuit, String modelVersion, String lastError) {
    }
}
