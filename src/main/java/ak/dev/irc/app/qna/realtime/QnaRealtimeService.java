package ak.dev.irc.app.qna.realtime;

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
 * Topic-based SSE manager — one topic per question.
 *
 * <p>Every viewer of a question detail page subscribes here and receives
 * answers, reanswers, reactions, accept/unaccept and feedback updates in
 * near real time.</p>
 *
 * <p>Per-instance only. Cross-instance fan-out is provided by
 * {@link QnaRealtimePublisher} / {@link QnaRealtimeSubscriber} over Redis
 * pub/sub.</p>
 */
@Slf4j
@Service
public class QnaRealtimeService {

    /** No server-side timeout — connection lives until the client disconnects. */
    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> topics = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionSeq = new AtomicLong();

    public SseEmitter subscribe(UUID questionId, UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(subscriptionSeq.incrementAndGet(), viewerId, emitter);

        CopyOnWriteArrayList<Subscription> subs =
                topics.computeIfAbsent(questionId, k -> new CopyOnWriteArrayList<>());
        subs.add(sub);

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<Subscription> bucket = topics.get(questionId);
            if (bucket != null) {
                bucket.remove(sub);
                if (bucket.isEmpty()) topics.remove(questionId, bucket);
            }
        };

        emitter.onCompletion(() -> {
            cleanup.run();
            log.debug("[QNA-SSE] Subscription completed question={} viewer={}", questionId, viewerId);
        });
        emitter.onTimeout(() -> {
            cleanup.run();
            log.debug("[QNA-SSE] Subscription timed out question={} viewer={}", questionId, viewerId);
        });
        emitter.onError(ex -> {
            cleanup.run();
            log.debug("[QNA-SSE] Subscription error question={} viewer={}: {}",
                    questionId, viewerId, ex.getMessage());
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "questionId", questionId.toString(),
                            "viewerId", viewerId == null ? "anonymous" : viewerId.toString(),
                            "timestamp", LocalDateTime.now().toString(),
                            "subscribers", subs.size()
                    )));
            log.info("[QNA-SSE] Subscribed question={} viewer={} (topic-subs={}, total-topics={})",
                    questionId, viewerId, subs.size(), topics.size());
        } catch (IOException ex) {
            cleanup.run();
            log.warn("[QNA-SSE] Failed handshake question={}: {}", questionId, ex.getMessage());
        }

        return emitter;
    }

    /**
     * Broadcast a payload to every subscriber of {@code questionId} on this instance.
     * Stale emitters are removed silently.
     */
    public void broadcast(UUID questionId, QnaRealtimeEvent event) {
        CopyOnWriteArrayList<Subscription> subs = topics.get(questionId);
        if (subs == null || subs.isEmpty()) {
            log.trace("[QNA-SSE] No local subscribers for question={} — skip", questionId);
            return;
        }

        String name = event.getEventType() == null ? "qna-event" : event.getEventType().name();

        for (Subscription sub : subs) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(event));
            } catch (IOException | IllegalStateException ex) {
                subs.remove(sub);
                if (subs.isEmpty()) topics.remove(questionId, subs);
                log.debug("[QNA-SSE] Removed stale emitter question={} viewer={}",
                        questionId, sub.viewerId);
            }
        }
    }

    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (topics.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        topics.forEach((questionId, subs) -> {
            for (Subscription sub : subs) {
                try {
                    sub.emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("timestamp", ts)));
                } catch (IOException | IllegalStateException ex) {
                    subs.remove(sub);
                    if (subs.isEmpty()) topics.remove(questionId, subs);
                }
            }
        });
    }

    public int topicCount() { return topics.size(); }

    public int subscriberCount(UUID questionId) {
        List<Subscription> subs = topics.get(questionId);
        return subs == null ? 0 : subs.size();
    }

    private record Subscription(long id, UUID viewerId, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
