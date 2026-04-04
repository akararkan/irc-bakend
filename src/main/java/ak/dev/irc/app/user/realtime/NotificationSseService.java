package ak.dev.irc.app.user.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active Server-Sent Event (SSE) connections.
 *
 * <p>One emitter per user is kept in a {@link ConcurrentHashMap}.  When a user
 * opens a new SSE connection any previous emitter for that user is cleanly
 * completed first (handles tab refresh / reconnect gracefully).</p>
 *
 * <p>A scheduled heartbeat is sent every 25 seconds to keep the connection
 * alive through proxies and load-balancers that would otherwise time it out.</p>
 */
@Slf4j
@Service
public class NotificationSseService {

    /** No server-side timeout — connections live until the client disconnects or the heartbeat fails. */
    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * Creates (or replaces) an SSE emitter for the given user and sends an
     * immediate {@code connected} event so the client knows the stream is live.
     */
    public SseEmitter subscribe(UUID userId) {
        // Close any existing emitter before replacing
        SseEmitter existing = emitters.remove(userId);
        if (existing != null) {
            try { existing.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> {
            emitters.remove(userId);
            log.debug("[SSE] Connection completed for user={}", userId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(userId);
            log.debug("[SSE] Connection timed out for user={}", userId);
        });
        emitter.onError(ex -> {
            emitters.remove(userId);
            log.debug("[SSE] Connection error for user={}: {}", userId, ex.getMessage());
        });

        emitters.put(userId, emitter);

        // Send an initial "connected" handshake event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "userId",    userId.toString(),
                            "timestamp", LocalDateTime.now().toString(),
                            "message",   "Real-time notifications stream active"
                    )));
            log.info("[SSE] User [{}] subscribed — active connections: {}", userId, emitters.size());
        } catch (IOException ex) {
            emitters.remove(userId);
            log.warn("[SSE] Failed to send initial event to user={}: {}", userId, ex.getMessage());
        }

        return emitter;
    }

    // ── Push ──────────────────────────────────────────────────────────────────

    /**
     * Pushes {@code payload} as a {@code notification} SSE event to the user.
     * If the emitter has gone stale it is silently removed.
     */
    public void push(UUID userId, Object payload) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            log.trace("[SSE] No active connection for user={} — notification skipped", userId);
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("notification")
                    .data(payload));
            log.debug("[SSE] Notification pushed to user={}", userId);
        } catch (IOException ex) {
            emitters.remove(userId);
            log.debug("[SSE] Removed stale emitter for user={}", userId);
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    /**
     * Sends a lightweight {@code heartbeat} event to every connected client every
     * 25 seconds.  Prevents proxies/load-balancers from closing idle connections
     * and lets the client detect disconnections quickly.
     */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;

        String ts = LocalDateTime.now().toString();
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("timestamp", ts)));
            } catch (IOException ex) {
                emitters.remove(userId);
                log.debug("[SSE] Removed stale emitter during heartbeat for user={}", userId);
            }
        });
        log.trace("[SSE] Heartbeat sent to {} connection(s)", emitters.size());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public long connectedCount() {
        return emitters.size();
    }

    public boolean isConnected(UUID userId) {
        return emitters.containsKey(userId);
    }
}

