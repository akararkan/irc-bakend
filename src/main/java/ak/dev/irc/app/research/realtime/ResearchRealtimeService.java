package ak.dev.irc.app.research.realtime;

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
 * Per-research SSE manager — every viewer of a research detail page gets
 * counter and content updates pushed live. Multi-tab safe; stale emitters
 * are pruned on the 25-second heartbeat or on any failed send.
 */
@Slf4j
@Service
public class ResearchRealtimeService {

    private static final long SSE_TIMEOUT_MS = 0L;

    private final Map<UUID, CopyOnWriteArrayList<Subscription>> topics = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public SseEmitter subscribe(UUID researchId, UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Subscription sub = new Subscription(seq.incrementAndGet(), viewerId, emitter);
        var bucket = topics.computeIfAbsent(researchId, k -> new CopyOnWriteArrayList<>());
        bucket.add(sub);

        Runnable cleanup = () -> {
            var b = topics.get(researchId);
            if (b != null) {
                b.remove(sub);
                if (b.isEmpty()) topics.remove(researchId, b);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "researchId", researchId.toString(),
                            "viewerId",   viewerId == null ? "anonymous" : viewerId.toString(),
                            "timestamp",  LocalDateTime.now().toString())));
        } catch (IOException ex) {
            cleanup.run();
        }
        return emitter;
    }

    public void broadcast(UUID researchId, ResearchRealtimeEvent event) {
        var bucket = topics.get(researchId);
        if (bucket == null || bucket.isEmpty()) return;
        String name = event.getEventType() == null ? "research-event" : event.getEventType().name();
        for (Subscription sub : bucket) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(event));
            } catch (IOException | IllegalStateException ex) {
                bucket.remove(sub);
                if (bucket.isEmpty()) topics.remove(researchId, bucket);
            }
        }
    }

    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        if (topics.isEmpty()) return;
        String ts = LocalDateTime.now().toString();
        topics.forEach((id, bucket) -> {
            for (Subscription sub : bucket) {
                try {
                    sub.emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestamp", ts)));
                } catch (IOException | IllegalStateException ex) {
                    bucket.remove(sub);
                    if (bucket.isEmpty()) topics.remove(id, bucket);
                }
            }
        });
    }

    public int topicCount() { return topics.size(); }

    public int subscriberCount(UUID researchId) {
        List<Subscription> b = topics.get(researchId);
        return b == null ? 0 : b.size();
    }

    private record Subscription(long id, UUID viewerId, SseEmitter emitter) {
        @Override public boolean equals(Object o) { return o instanceof Subscription s && s.id == this.id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }
}
