package ak.dev.irc.app.post.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryRealtimeService {

    private static final long SSE_TIMEOUT_MS   = 5 * 60 * 1_000L;
    private static final long HEARTBEAT_MS     = 25_000L;

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> topics = new ConcurrentHashMap<>();
    private final StoryRealtimePublisher   publisher;
    private final StoryRealtimeBroadcaster broadcaster;

    public SseEmitter subscribe(UUID storyId, UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        topics.computeIfAbsent(storyId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = topics.get(storyId);
            // Two-arg remove: between isEmpty() and remove another thread can
            // add a fresh emitter to this bucket; the conditional form only
            // unmaps the bucket if it is still the same (now-empty) instance.
            if (list != null) { list.remove(emitter); if (list.isEmpty()) topics.remove(storyId, list); }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"storyId\":\"" + storyId + "\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    public void broadcast(UUID storyId, StoryRealtimeEvent event) {
        List<SseEmitter> emitters = topics.get(storyId);
        if (emitters == null || emitters.isEmpty()) return;

        String eventName = event.getEventType() == null
                ? "story-event" : event.getEventType().name().toLowerCase();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(event));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }
    }

    /** Called by services — defers until afterCommit when inside a transaction. */
    public void broadcastEvent(UUID storyId, StoryRealtimeEvent event) {
        broadcaster.broadcast(storyId, event);
    }

    /**
     * One shared heartbeat tick for every open story-subscription emitter.
     * Replaces the previous per-connection {@code newSingleThreadScheduledExecutor}
     * which leaked a JVM thread per SSE client (10k connections → 10k threads → OOM).
     * Dead emitters are removed inline so a single bad connection can't accumulate.
     */
    /** Open story topics — ops SSE fleet visibility (operations.md §6.2). */
    public int topicCount() {
        return topics.size();
    }

    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        if (topics.isEmpty()) return;
        topics.forEach((storyId, bucket) -> {
            for (SseEmitter emitter : bucket) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                } catch (Exception e) {
                    bucket.remove(emitter);
                    if (bucket.isEmpty()) topics.remove(storyId, bucket);
                }
            }
        });
    }
}
