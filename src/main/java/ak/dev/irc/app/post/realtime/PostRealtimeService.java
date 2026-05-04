package ak.dev.irc.app.post.realtime;

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
 * Topic-based SSE manager — one topic per post.
 *
 * <p>Many clients can subscribe to the same post (every viewer of the
 * detail page or the feed card) and each gets every reaction, comment,
 * reply and view-count update broadcast on that post.</p>
 *
 * <p>Per-instance only. Cross-instance fan-out is provided by
 * {@link PostRealtimePublisher} / PostRealtimeSubscriber over Redis pub/sub.</p>
 */
@Slf4j
@Service
public class PostRealtimeService {

    /** No server-side timeout — connection lives until the client disconnects. */
    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> topics = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionSeq = new AtomicLong();

    // ── Subscribe ─────────────────────────────────────────────────────────

    public SseEmitter subscribe(UUID postId, UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(subscriptionSeq.incrementAndGet(), viewerId, emitter);

        CopyOnWriteArrayList<Subscription> subs =
                topics.computeIfAbsent(postId, k -> new CopyOnWriteArrayList<>());
        subs.add(sub);

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<Subscription> bucket = topics.get(postId);
            if (bucket != null) {
                bucket.remove(sub);
                if (bucket.isEmpty()) topics.remove(postId, bucket);
            }
        };

        emitter.onCompletion(() -> {
            cleanup.run();
            log.debug("[POST-SSE] Subscription completed post={} viewer={}", postId, viewerId);
        });
        emitter.onTimeout(() -> {
            cleanup.run();
            log.debug("[POST-SSE] Subscription timed out post={} viewer={}", postId, viewerId);
        });
        emitter.onError(ex -> {
            cleanup.run();
            log.debug("[POST-SSE] Subscription error post={} viewer={}: {}", postId, viewerId, ex.getMessage());
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "postId",     postId.toString(),
                            "viewerId",   viewerId == null ? "anonymous" : viewerId.toString(),
                            "timestamp",  LocalDateTime.now().toString(),
                            "subscribers", subs.size()
                    )));
            log.info("[POST-SSE] Subscribed post={} viewer={} (topic-subs={}, total-topics={})",
                    postId, viewerId, subs.size(), topics.size());
        } catch (IOException ex) {
            cleanup.run();
            log.warn("[POST-SSE] Failed handshake post={}: {}", postId, ex.getMessage());
        }

        return emitter;
    }

    // ── Push ──────────────────────────────────────────────────────────────

    /**
     * Broadcast a payload to every subscriber of {@code postId} on this instance.
     * Stale emitters are removed silently.
     */
    public void broadcast(UUID postId, PostRealtimeEvent event) {
        CopyOnWriteArrayList<Subscription> subs = topics.get(postId);
        if (subs == null || subs.isEmpty()) {
            log.trace("[POST-SSE] No local subscribers for post={} — skip", postId);
            return;
        }

        String name = event.getEventType() == null ? "post-event" : event.getEventType().name();

        for (Subscription sub : subs) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(event));
            } catch (IOException | IllegalStateException ex) {
                subs.remove(sub);
                if (subs.isEmpty()) topics.remove(postId, subs);
                log.debug("[POST-SSE] Removed stale emitter post={} viewer={}", postId, sub.viewerId);
            }
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────

    /**
     * Keep proxy / load-balancer connections alive and let clients detect
     * dead subscriptions quickly.
     */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (topics.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        topics.forEach((postId, subs) -> {
            for (Subscription sub : subs) {
                try {
                    sub.emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", ts)));
                } catch (IOException | IllegalStateException ex) {
                    subs.remove(sub);
                    if (subs.isEmpty()) topics.remove(postId, subs);
                }
            }
        });
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    public int topicCount() { return topics.size(); }

    public int subscriberCount(UUID postId) {
        List<Subscription> subs = topics.get(postId);
        return subs == null ? 0 : subs.size();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private record Subscription(long id, UUID viewerId, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
