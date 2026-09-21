package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real push delivery through Expo's push service ({@code POST
 * https://exp.host/--/api/v2/push/send}), which fronts both APNs and FCM. The
 * mobile app registers {@code ExponentPushToken[...]} rows with provider
 * {@code EXPO}; tokens of any other provider pass through untouched (returned
 * as delivered) so {@link FcmPushSender} can coexist without this one pruning
 * its rows.
 *
 * <p>Built on the JDK's {@code java.net.http.HttpClient}, matching
 * {@code ModerationInferenceClient} / {@code MediaControlClient} — the
 * project's only other outbound HTTP clients.</p>
 *
 * <p>Failure semantics (the part that matters): only a provider ticket that
 * says {@code DeviceNotRegistered} marks a token dead — that is the caller's
 * signal to delete the row (spec §8 token hygiene). Timeouts, non-200s and
 * IOExceptions are transient: log at warn, report delivered, never prune.</p>
 *
 * <p>Reached only through {@link PushSenderRouter}, which owns the
 * per-provider routing; {@link NoOpPushSender} replaces the whole stack when
 * {@code app.push.provider=noop}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(PushSenderRouter.NOT_NOOP)
public class ExpoPushSender implements PushSender {

    /** Expo accepts at most 100 messages per request. */
    private static final int MAX_BATCH = 100;

    private final ObjectMapper objectMapper;

    @Value("${app.push.expo.url:https://exp.host/--/api/v2/push/send}")
    private String endpoint;

    @Value("${app.push.expo.timeout-ms:10000}")
    private long timeoutMs;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        log.info("[PUSH] Expo sender active → {} (timeout {}ms)", endpoint, timeoutMs);
    }

    @Override
    public boolean send(PushToken token, String title, String body,
                        Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        return sendAll(List.of(token), title, body, data, channelId, badge, dataOnly).isEmpty();
    }

    @Override
    public List<PushToken> sendAll(List<PushToken> tokens, String title, String body,
                                   Map<String, String> data, String channelId, Integer badge,
                                   boolean dataOnly) {
        List<PushToken> deliverable = tokens.stream().filter(this::isExpoToken).toList();
        List<PushToken> dead = new ArrayList<>();
        for (int start = 0; start < deliverable.size(); start += MAX_BATCH) {
            List<PushToken> chunk = deliverable.subList(start,
                    Math.min(deliverable.size(), start + MAX_BATCH));
            dead.addAll(postChunk(chunk, title, body, data, channelId, badge, dataOnly));
        }
        return dead;
    }

    /** One HTTP round-trip for up to {@link #MAX_BATCH} messages. */
    private List<PushToken> postChunk(List<PushToken> chunk, String title, String body,
                                      Map<String, String> data, String channelId, Integer badge,
                                      boolean dataOnly) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (PushToken t : chunk) {
            messages.add(message(t, title, body, data, channelId, badge, dataOnly));
        }
        JsonNode response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(messages)))
                    .build();
            HttpResponse<String> http = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (http.statusCode() != 200) {
                // 429/5xx are transient — never prune on them.
                log.warn("[PUSH] Expo answered {} for a batch of {} — treating as delivered",
                        http.statusCode(), chunk.size());
                return List.of();
            }
            response = objectMapper.readTree(http.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PUSH] Expo send interrupted — treating as delivered");
            return List.of();
        } catch (Exception e) {
            // Timeouts / IOExceptions / bad JSON — transient, do NOT prune.
            log.warn("[PUSH] Expo send failed ({}) — treating as delivered", e.getMessage());
            return List.of();
        }
        return deadTokensOf(chunk, response);
    }

    /** One Expo message. Sound is 'default' — per-channel sounds are the client's
     *  Android channel configuration, keyed by {@code channelId}.
     *
     *  <p>{@code dataOnly} omits {@code title}/{@code body}/{@code sound} —
     *  title/body still ride inside {@code data} — so the message reaches the
     *  client as a silent/background push instead of an OS-rendered alert;
     *  see {@link PushSender#send} for why. </p> */
    private ObjectNode message(PushToken t, String title, String body,
                               Map<String, String> data, String channelId, Integer badge,
                               boolean dataOnly) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("to", t.getToken());
        if (!dataOnly) {
            msg.put("title", title);
            msg.put("body", body);
            msg.put("sound", "default");
        }
        msg.put("priority", "high");
        if (channelId != null) msg.put("channelId", channelId);
        if (badge != null)     msg.put("badge", badge);
        if (data != null && !data.isEmpty()) {
            ObjectNode d = msg.putObject("data");
            data.forEach((k, v) -> { if (v != null) d.put(k, v); });
        }
        return msg;
    }

    /**
     * Parse the ticket array — one ticket per message, in request order. Only
     * {@code DeviceNotRegistered} marks a token dead; every other error
     * (MessageTooBig, rate limits …) is logged and the token kept.
     */
    private List<PushToken> deadTokensOf(List<PushToken> chunk, JsonNode response) {
        List<PushToken> dead = new ArrayList<>();
        JsonNode tickets = response.path("data");
        if (!tickets.isArray()) {
            log.warn("[PUSH] Expo response carried no tickets: {}", response.path("errors"));
            return dead;
        }
        for (int i = 0; i < tickets.size() && i < chunk.size(); i++) {
            JsonNode ticket = tickets.get(i);
            if (!"error".equals(ticket.path("status").asText())) continue;
            String error = ticket.path("details").path("error").asText("");
            if ("DeviceNotRegistered".equals(error)) {
                dead.add(chunk.get(i));
            } else {
                log.warn("[PUSH] Expo ticket error {} for {}: {}",
                        error, chunk.get(i).getPlatform(), ticket.path("message").asText(""));
            }
        }
        return dead;
    }

    /** EXPO-provider rows only; foreign providers (a raw FCM/APNS token) are
     *  not ours to deliver — or to prune. */
    private boolean isExpoToken(PushToken t) {
        boolean ours = "EXPO".equalsIgnoreCase(t.getProvider())
                || (t.getToken() != null && t.getToken().startsWith("ExponentPushToken"));
        if (!ours) {
            log.debug("[PUSH] skipping non-Expo token (provider {})", t.getProvider());
        }
        return ours;
    }
}
