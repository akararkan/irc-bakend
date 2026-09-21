package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Direct push delivery through the FCM HTTP v1 API ({@code POST
 * https://fcm.googleapis.com/v1/projects/{project_id}/messages:send}) for
 * native device tokens the mobile app registers with provider {@code FCM}.
 * Tokens of any other provider pass through untouched (returned as delivered)
 * — Expo rows belong to {@link ExpoPushSender}, and neither sender ever prunes
 * the other's.
 *
 * <p>Credentials are a Firebase service-account JSON
 * ({@code app.push.fcm.service-account-file}); OAuth2 is the standard
 * JWT-bearer grant — an RS256 assertion signed with the file's
 * {@code private_key} (via the JJWT already on the classpath) exchanged for an
 * access token, cached until shortly before expiry. If the file is missing at
 * startup the sender logs one warning and reports every send as delivered: no
 * pruning, no crash, and dropping the file in requires a restart.</p>

 * <p>Built on the JDK's {@code java.net.http.HttpClient}, matching
 * {@link ExpoPushSender} and the project's other outbound HTTP clients.</p>
 *
 * <p>Failure semantics (the part that matters): only a provider response that
 * says the token is unregistered — HTTP 404, or 400 {@code INVALID_ARGUMENT}
 * whose details carry {@code UNREGISTERED} — marks a token dead (spec §8 token
 * hygiene). Timeouts, 5xx, auth hiccups and IOExceptions are transient: log at
 * warn, report delivered, never prune.</p>
 *
 * <p>Reached only through {@link PushSenderRouter}, which owns the
 * per-provider routing; {@link NoOpPushSender} replaces the whole stack when
 * {@code app.push.provider=noop}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(PushSenderRouter.NOT_NOOP)
public class FcmPushSender implements PushSender {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE     = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String GRANT     = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private final ObjectMapper objectMapper;

    @Value("${app.push.fcm.service-account-file:${user.dir}/firebase-service-account.json}")
    private String serviceAccountFile;

    @Value("${app.push.fcm.timeout-ms:10000}")
    private long timeoutMs;

    private HttpClient httpClient;

    // ── from the service-account JSON; null while unconfigured ──
    private String clientEmail;
    private PrivateKey privateKey;
    private String sendEndpoint;

    // ── cached OAuth2 access token, refreshed ~60s before expiry ──
    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        Path file = Path.of(serviceAccountFile);
        if (!Files.isRegularFile(file)) {
            log.warn("[PUSH] FCM service-account file not found at {} — FCM sends will be " +
                     "treated as delivered (no pruning). Download it from Firebase console → " +
                     "Project settings → Service accounts → Generate new private key, then " +
                     "restart the server.", file.toAbsolutePath());
            return;
        }
        try {
            JsonNode sa = objectMapper.readTree(Files.readString(file));
            clientEmail  = sa.path("client_email").asText(null);
            privateKey   = parsePrivateKey(sa.path("private_key").asText(""));
            sendEndpoint = "https://fcm.googleapis.com/v1/projects/"
                    + sa.path("project_id").asText() + "/messages:send";
            log.info("[PUSH] FCM sender active → {} as {} (timeout {}ms)",
                    sendEndpoint, clientEmail, timeoutMs);
        } catch (Exception e) {
            clientEmail = null;
            privateKey  = null;
            log.warn("[PUSH] FCM service-account file {} unreadable ({}) — FCM sends will be " +
                     "treated as delivered (no pruning). Fix the file and restart the server.",
                    file.toAbsolutePath(), e.getMessage());
        }
    }

    @Override
    public boolean send(PushToken token, String title, String body,
                        Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        if (!isFcmToken(token)) return true;   // not ours to deliver — or to prune
        if (privateKey == null) return true;   // unconfigured: warned once at startup
        String bearer;
        try {
            bearer = bearerToken();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PUSH] FCM token exchange interrupted — treating as delivered");
            return true;
        } catch (Exception e) {
            log.warn("[PUSH] FCM token exchange failed ({}) — treating as delivered", e.getMessage());
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sendEndpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(
                                    message(token, title, body, data, channelId, badge, dataOnly))))
                    .build();
            HttpResponse<String> http = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return handleResponse(token, http);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PUSH] FCM send interrupted — treating as delivered");
            return true;
        } catch (Exception e) {
            // Timeouts / IOExceptions — transient, do NOT prune.
            log.warn("[PUSH] FCM send failed ({}) — treating as delivered", e.getMessage());
            return true;
        }
    }

    // ── response handling ──────────────────────────────────────────────────

    /** Only an UNREGISTERED verdict returns false; everything else is transient. */
    private boolean handleResponse(PushToken token, HttpResponse<String> http) {
        int status = http.statusCode();
        if (status == 200) return true;
        if (status == 404 || isUnregistered(http.body())) {
            log.debug("[PUSH] FCM reported token unregistered ({}) — pruning", token.getPlatform());
            return false;
        }
        if (status == 401) {
            // Access token rejected — drop the cache so the next send re-mints.
            synchronized (this) { accessToken = null; }
        }
        log.warn("[PUSH] FCM answered {} for {} — treating as delivered: {}",
                status, token.getPlatform(), http.body());
        return true;
    }

    /** 400 INVALID_ARGUMENT carries the UNREGISTERED verdict in error.details[].errorCode. */
    private boolean isUnregistered(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            for (JsonNode detail : error.path("details")) {
                if ("UNREGISTERED".equals(detail.path("errorCode").asText())) return true;
            }
        } catch (Exception ignored) {
            // Not JSON — fall through to transient handling.
        }
        return false;
    }

    // ── message body ───────────────────────────────────────────────────────

    /** One FCM v1 message. Sound is 'default' — per-channel sounds are the client's
     *  Android channel configuration, keyed by {@code channel_id}.
     *
     *  <p>{@code dataOnly} omits the top-level {@code notification} block (and
     *  the {@code android.notification} block, which only has meaning
     *  alongside one) entirely — title/body still ride inside {@code data} so
     *  a client background task can build its own UI. A message WITH a
     *  {@code notification} block is auto-rendered by the OS on a killed
     *  Android app, which never gives client JS a chance to run; a data-only
     *  message is the only shape that does.</p> */
    private ObjectNode message(PushToken t, String title, String body,
                               Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode msg = root.putObject("message");
        msg.put("token", t.getToken());
        if (!dataOnly) {
            ObjectNode notification = msg.putObject("notification");
            notification.put("title", title);
            notification.put("body", body);
        }
        if (data != null && !data.isEmpty()) {
            ObjectNode d = msg.putObject("data");
            data.forEach((k, v) -> { if (v != null) d.put(k, v); });
        }
        ObjectNode android = msg.putObject("android");
        android.put("priority", "high");
        if (!dataOnly) {
            ObjectNode androidNotification = android.putObject("notification");
            if (channelId != null) androidNotification.put("channel_id", channelId);
            androidNotification.put("sound", "default");
            if (badge != null) androidNotification.put("notification_count", badge);
        }
        return root;
    }

    // ── OAuth2 (JWT-bearer grant) ──────────────────────────────────────────

    /** Cached access token, re-minted once within 60s of expiry. */
    private synchronized String bearerToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minusSeconds(60))) {
            return accessToken;
        }
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .audience().single(TOKEN_URI)
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=" + URLEncoder.encode(GRANT, StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URI))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> http = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (http.statusCode() != 200) {
            throw new IllegalStateException("OAuth2 token endpoint answered " + http.statusCode());
        }
        JsonNode tokenResponse = objectMapper.readTree(http.body());
        accessToken       = tokenResponse.path("access_token").asText();
        accessTokenExpiry = now.plusSeconds(tokenResponse.path("expires_in").asLong(3600));
        return accessToken;
    }

    /** PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}) → RSA private key. */
    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** FCM-provider rows only; foreign providers (Expo rows) are not ours to
     *  deliver — or to prune. */
    private boolean isFcmToken(PushToken t) {
        boolean ours = "FCM".equalsIgnoreCase(t.getProvider());
        if (!ours) {
            log.debug("[PUSH] skipping non-FCM token (provider {})", t.getProvider());
        }
        return ours;
    }
}
