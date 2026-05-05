package ak.dev.irc.app.user.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages all active Server-Sent Event (SSE) connections for notifications.
 *
 * <p>Multiple emitters per user are supported (one per open browser tab /
 * device) — pushes fan out to every active emitter so the inbox stays in
 * sync everywhere. Stale emitters are pruned silently on send failure or on
 * the 25-second heartbeat.</p>
 */
@Slf4j
@Service
public class NotificationSseService {

    /** No server-side timeout — connections live until the client disconnects. */
    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionSeq = new AtomicLong();

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * Adds a new SSE emitter for the user. Existing emitters for the same user
     * (other tabs / devices) are kept open — pushes broadcast to all of them.
     */
    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(subscriptionSeq.incrementAndGet(), emitter);

        CopyOnWriteArrayList<Subscription> bucket =
                emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        bucket.add(sub);

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<Subscription> b = emittersByUser.get(userId);
            if (b != null) {
                b.remove(sub);
                if (b.isEmpty()) emittersByUser.remove(userId, b);
            }
        };

        emitter.onCompletion(() -> {
            cleanup.run();
            log.debug("[SSE] Connection completed for user={}", userId);
        });
        emitter.onTimeout(() -> {
            cleanup.run();
            log.debug("[SSE] Connection timed out for user={}", userId);
        });
        emitter.onError(ex -> {
            cleanup.run();
            log.debug("[SSE] Connection error for user={}: {}", userId, ex.getMessage());
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "userId",     userId.toString(),
                            "timestamp",  LocalDateTime.now().toString(),
                            "tabs",       bucket.size(),
                            "message",    "Real-time notifications stream active"
                    )));
            log.info("[SSE] User [{}] subscribed — tabs={} totalUsers={}",
                    userId, bucket.size(), emittersByUser.size());
        } catch (IOException ex) {
            cleanup.run();
            log.warn("[SSE] Failed handshake for user={}: {}", userId, ex.getMessage());
        }

        return emitter;
    }

    // ── Push ──────────────────────────────────────────────────────────────────

    /**
     * Pushes {@code payload} under the given SSE {@code eventName} to every open
     * emitter for the user (multi-tab safe). Stale emitters are pruned silently.
     */
    public void push(UUID userId, String eventName, Object payload) {
        CopyOnWriteArrayList<Subscription> bucket = emittersByUser.get(userId);
        if (bucket == null || bucket.isEmpty()) {
            log.trace("[SSE] No active connection for user={} — {} skipped", userId, eventName);
            return;
        }
        for (Subscription sub : bucket) {
            try {
                sub.emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload));
            } catch (IOException | IllegalStateException ex) {
                bucket.remove(sub);
                if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
                log.debug("[SSE] Removed stale emitter for user={}", userId);
            }
        }
        log.debug("[SSE] {} pushed to user={} ({} tab(s))", eventName, userId, bucket.size());
    }

    /** Backwards-compatible default — emits a {@code notification} event. */
    public void push(UUID userId, Object payload) {
        push(userId, "notification", payload);
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (emittersByUser.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        emittersByUser.forEach((userId, bucket) -> {
            for (Subscription sub : bucket) {
                try {
                    sub.emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", ts)));
                } catch (IOException | IllegalStateException ex) {
                    bucket.remove(sub);
                    if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
                }
            }
        });
        log.trace("[SSE] Heartbeat sent to {} user(s)", emittersByUser.size());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public long connectedUserCount() {
        return emittersByUser.size();
    }

    public boolean isConnected(UUID userId) {
        List<Subscription> b = emittersByUser.get(userId);
        return b != null && !b.isEmpty();
    }

    public int tabCount(UUID userId) {
        List<Subscription> b = emittersByUser.get(userId);
        return b == null ? 0 : b.size();
    }

    private record Subscription(long id, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
