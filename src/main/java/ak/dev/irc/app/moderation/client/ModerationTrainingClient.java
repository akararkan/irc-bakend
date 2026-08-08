package ak.dev.irc.app.moderation.client;

import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.entity.ModerationGoldenCase;
import ak.dev.irc.app.moderation.entity.ModerationTrainingExample;
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
import java.util.List;

/**
 * The Java side of the {@code /v1/train} contract (MODERATION_ROADMAP.md §10.3,
 * §12.4). Same JDK-HttpClient approach as {@link ModerationInferenceClient}.
 *
 * <p>The dataset is POSTed in the request body rather than exposed as a URL the
 * container fetches. §9 requires the Python side never to touch the application
 * database, and a body keeps it that way without standing up a signed-URL
 * export endpoint that would itself have to be secured — the row cap in
 * {@code app.moderation.training.max-examples} keeps the payload sane.</p>
 *
 * <p>Training is genuinely long-running, so this only ever <em>starts</em> a job
 * and reads its status. Nothing here blocks on a fine-tune finishing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationTrainingClient {

    private static final String HEADER_API_KEY = "X-API-Key";

    private final ModerationProperties properties;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                // HTTP/1.1 explicitly — same uvicorn h2c-upgrade trap documented in
                // ModerationInferenceClient.init(). A POST body silently disappears
                // against the default HTTP_2 client and FastAPI answers 422.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Kicks off a fine-tune and returns the job id. The container answers as soon
     * as the job is queued — the run itself proceeds in its background task.
     */
    public StartedJob startTraining(List<ModerationTrainingExample> examples,
                                    List<ModerationGoldenCase> goldenSet,
                                    String baseCheckpoint,
                                    String notes) {
        ObjectNode body = objectMapper.createObjectNode();

        ArrayNode items = body.putArray("examples");
        for (ModerationTrainingExample example : examples) {
            ObjectNode node = items.addObject();
            node.put("text", example.getText());
            ObjectNode labels = node.putObject("labels");
            example.labelMap().forEach(labels::put);
        }

        ArrayNode golden = body.putArray("golden_set");
        for (ModerationGoldenCase goldenCase : goldenSet) {
            ObjectNode node = golden.addObject();
            node.put("text", goldenCase.getText());
            ObjectNode labels = node.putObject("labels");
            goldenCase.labelMap().forEach(labels::put);
        }

        if (baseCheckpoint != null && !baseCheckpoint.isBlank()) {
            body.put("base_checkpoint", baseCheckpoint);
        }
        if (notes != null && !notes.isBlank()) {
            body.put("notes", notes);
        }

        ModerationProperties.Training cfg = properties.getTraining();
        if (!cfg.getCallbackUrl().isBlank()) {
            body.put("callback_url", cfg.getCallbackUrl());
            if (!cfg.getCallbackToken().isBlank()) {
                body.put("callback_token", cfg.getCallbackToken());
            }
        }

        JsonNode response = exchange("POST", "/v1/train", body);
        String jobId = response.path("job_id").asText(null);
        if (jobId == null || jobId.isBlank()) {
            throw new InferenceUnavailableException("training service returned no job_id");
        }
        log.info("[MODERATION] training job {} started — {} examples, {} golden cases",
                jobId, examples.size(), goldenSet.size());
        return new StartedJob(jobId, response.path("status").asText("queued"));
    }

    /** Polls a job. Returns the raw node so the caller can persist metrics verbatim. */
    public JsonNode jobStatus(String jobId) {
        return exchange("GET", "/v1/train/" + jobId, null);
    }

    /** Artifacts present in the shared volume — used to reconcile the registry. */
    public JsonNode versions() {
        return exchange("GET", "/v1/versions", null);
    }

    public Health health() {
        try {
            exchange("GET", "/healthz", null);
            return new Health(true, null);
        } catch (Exception ex) {
            return new Health(false, ex.getMessage());
        }
    }

    private JsonNode exchange(String method, String path, ObjectNode body) {
        ModerationProperties.Training cfg = properties.getTraining();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new InferenceUnavailableException("%s %s → HTTP %d: %s"
                        .formatted(method, path, response.statusCode(), response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InferenceUnavailableException("interrupted calling " + path, ie);
        } catch (InferenceUnavailableException known) {
            throw known;
        } catch (Exception ex) {
            throw new InferenceUnavailableException(
                    "%s %s failed: %s".formatted(method, path, ex.getMessage()), ex);
        }
    }

    public record StartedJob(String jobId, String status) {
    }

    public record Health(boolean up, String error) {
    }
}
