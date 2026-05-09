package ak.dev.irc.app.activity.realtime;

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
 * Per-user SSE manager — every user has a private channel that streams
 * their own {@link UserActivityRealtimeEvent}s the moment a row is written.
 *
 * <p>Multiple tabs / devices may subscribe simultaneously; each gets every
 * event for that user. Cross-instance fan-out is handled by
 * {@link UserActivityRealtimePublisher} / {@link UserActivityRealtimeSubscriber}
 * over Redis pub/sub.</p>
 */
@Slf4j
@Service
public class UserActivityRealtimeService {

    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(seq.incrementAndGet(), emitter);
        var bucket = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        bucket.add(sub);

        Runnable cleanup = () -> {
            var b = emittersByUser.get(userId);
            if (b != null) {
                b.remove(sub);
                if (b.isEmpty()) emittersByUser.remove(userId, b);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "userId",     userId.toString(),
                            "timestamp",  LocalDateTime.now().toString(),
                            "subscribers", bucket.size())));
            log.debug("[ACTIVITY-SSE] user [{}] subscribed (tabs={}, totalUsers={})",
                    userId, bucket.size(), emittersByUser.size());
        } catch (IOException ex) {
            cleanup.run();
        }
        return emitter;
    }

    public void broadcast(UUID userId, UserActivityRealtimeEvent event) {
        if (userId == null || event == null) return;
        var bucket = emittersByUser.get(userId);
        if (bucket == null || bucket.isEmpty()) return;

        String name = event.getActivityType() == null ? "activity" : event.getActivityType().name();
        for (Subscription sub : bucket) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(event));
            } catch (IOException | IllegalStateException ex) {
                bucket.remove(sub);
                if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
            }
        }
    }

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
    }

    public int userCount() { return emittersByUser.size(); }

    private record Subscription(long id, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
