package ak.dev.irc.app.chat.realtime;

import ak.dev.irc.app.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
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
 * Holds every live chat SSE connection on <b>this</b> instance, keyed by userId.
 * One user may hold several emitters (tabs/devices); a push fans out to all of
 * them. Cross-instance delivery is handled upstream by Redis pub/sub
 * ({@link ChatRedisPublisher}/{@link ChatRedisSubscriber}) — this service only
 * ever writes to locally-held emitters.
 *
 * <p>Cloned from the platform's proven {@code NotificationSseService}: finite
 * long timeout (never {@code 0}), 2&nbsp;KB flush comment to defeat proxy
 * buffering, {@code reconnectTime}, per-user emitter cap with LRU eviction, and
 * a single shared {@code @Scheduled} heartbeat sweep (never a thread per
 * connection).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSseService {

    private static final long SSE_TIMEOUT_MS = 24L * 60L * 60L * 1000L; // 24h
    private static final long HEARTBEAT_MS   = 15_000L;
    private static final int  MAX_EMITTERS_PER_USER = 5;

    private final PresenceService presenceService;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    // ── Subscribe ───────────────────────────────────────────────────────────

    public SseEmitter subscribe(UUID userId) {
        presenceService.markOnline(userId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(seq.incrementAndGet(), emitter);

        CopyOnWriteArrayList<Subscription> bucket;
        while (true) {
            bucket = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

            // Evict oldest tabs beyond the cap (LRU) so one account can't exhaust memory.
            while (bucket.size() >= MAX_EMITTERS_PER_USER) {
                try {
                    Subscription oldest = bucket.get(0);
                    try { oldest.emitter().complete(); } catch (Exception ignore) { /* best-effort */ }
                    bucket.remove(oldest);
                } catch (IndexOutOfBoundsException raced) { break; } // emptied concurrently
            }
            bucket.add(sub);
            // A concurrent push/heartbeat may have removed the then-empty bucket from
            // the map between computeIfAbsent and add — if so this subscription would
            // be orphaned (never receive a push); re-check and retry on loss.
            if (emittersByUser.get(userId) == bucket) break;
            bucket.remove(sub);
        }

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<Subscription> b = emittersByUser.get(userId);
            if (b != null) {
                b.remove(sub);
                if (b.isEmpty()) emittersByUser.remove(userId, b);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            // Serialized on the subscription: SseEmitter.send is not safe to call
            // concurrently, and a heartbeat/push can fire while the handshake runs.
            synchronized (sub) {
                emitter.send(SseEmitter.event().reconnectTime(3000L));
                emitter.send(SseEmitter.event().comment(" ".repeat(2048)));
                emitter.send(SseEmitter.event()
                        .name("connected")
                        .data(Map.of(
                                "userId", userId.toString(),
                                "timestamp", LocalDateTime.now().toString(),
                                "tabs", bucket.size(),
                                "message", "Chat stream active")));
            }
            log.info("[CHAT-SSE] user={} subscribed — tabs={} totalUsers={}",
                    userId, bucket.size(), emittersByUser.size());
        } catch (IOException ex) {
            cleanup.run();
            log.warn("[CHAT-SSE] handshake failed for user={}: {}", userId, ex.getMessage());
        }
        return emitter;
    }

    // ── Push ────────────────────────────────────────────────────────────────

    /** Push {@code payload} under SSE event {@code eventName} to every tab of the user. */
    public void push(UUID userId, String eventName, Object payload) {
        CopyOnWriteArrayList<Subscription> bucket = emittersByUser.get(userId);
        if (bucket == null || bucket.isEmpty()) return;
        for (Subscription sub : bucket) {
            try {
                synchronized (sub) { // one writer at a time per emitter (push vs heartbeat)
                    sub.emitter.send(SseEmitter.event().name(eventName).data(payload));
                }
            } catch (IOException | IllegalStateException ex) {
                bucket.remove(sub);
                if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
            }
        }
    }

    // ── Heartbeat ───────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        if (emittersByUser.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        emittersByUser.forEach((userId, bucket) -> {
            // Refresh presence while any tab is open; on last close it TTL-expires.
            presenceService.markOnline(userId);
            for (Subscription sub : bucket) {
                try {
                    synchronized (sub) { // never interleave with a concurrent push
                        sub.emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestamp", ts)));
                    }
                } catch (IOException | IllegalStateException ex) {
                    bucket.remove(sub);
                    if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
                }
            }
        });
    }

    // ── Stats ───────────────────────────────────────────────────────────────

    public boolean isConnected(UUID userId) {
        List<Subscription> b = emittersByUser.get(userId);
        return b != null && !b.isEmpty();
    }

    public long connectedUserCount() { return emittersByUser.size(); }

    private record Subscription(long id, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
