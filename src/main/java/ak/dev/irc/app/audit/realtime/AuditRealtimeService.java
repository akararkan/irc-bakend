package ak.dev.irc.app.audit.realtime;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-instance SSE manager for the admin audit stream. Multiple admins (and
 * multiple tabs per admin) can subscribe; every event is fanned out to all
 * open emitters with a 25-second heartbeat keeping connections alive through
 * proxies.
 */
@Slf4j
@Service
public class AuditRealtimeService {

    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> emittersByAdmin = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public SseEmitter subscribe(UUID adminId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(seq.incrementAndGet(), emitter);
        var bucket = emittersByAdmin.computeIfAbsent(adminId, k -> new CopyOnWriteArrayList<>());
        bucket.add(sub);

        Runnable cleanup = () -> {
            var b = emittersByAdmin.get(adminId);
            if (b != null) {
                b.remove(sub);
                if (b.isEmpty()) emittersByAdmin.remove(adminId, b);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "adminId",   adminId.toString(),
                            "timestamp", LocalDateTime.now().toString())));
            log.info("[AUDIT-SSE] admin [{}] subscribed (admins={})", adminId, emittersByAdmin.size());
        } catch (IOException ex) {
            cleanup.run();
        }
        return emitter;
    }

    /**
     * Broadcast {@code log} to every connected admin on this instance.
     * Audit volume can be high — stale emitters are pruned silently to
     * keep the broadcast loop fast.
     */
    public void broadcast(AuditLogResponse log) {
        if (emittersByAdmin.isEmpty()) return;
        emittersByAdmin.forEach((adminId, bucket) -> {
            for (Subscription sub : bucket) {
                try {
                    sub.emitter.send(SseEmitter.event().name("audit").data(log));
                } catch (IOException | IllegalStateException ex) {
                    bucket.remove(sub);
                    if (bucket.isEmpty()) emittersByAdmin.remove(adminId, bucket);
                }
            }
        });
    }

    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (emittersByAdmin.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        emittersByAdmin.forEach((adminId, bucket) -> {
            for (Subscription sub : bucket) {
                try {
                    sub.emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestamp", ts)));
                } catch (IOException | IllegalStateException ex) {
                    bucket.remove(sub);
                    if (bucket.isEmpty()) emittersByAdmin.remove(adminId, bucket);
                }
            }
        });
    }

    public int adminCount() { return emittersByAdmin.size(); }

    private record Subscription(long id, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
