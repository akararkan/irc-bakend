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

/**
 * Per-user SSE service for story-tray updates.
 *
 * <p>Each logged-in user keeps ONE open connection to
 * {@code GET /api/v1/stories/tray/stream}. When someone they follow
 * posts a story, this service pushes a {@link StoryTrayEvent} to their
 * emitter so the tray ring lights up instantly — no polling required.</p>
 *
 * <p>Cross-instance delivery: events are published to Redis
 * {@code irc:story-tray:{viewerId}} by {@link StoryTrayRealtimePublisher},
 * picked up by {@link StoryTrayRealtimeSubscriber} on every instance,
 * and forwarded here for local fan-out to open emitters.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryTrayRealtimeService {

    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1_000L; // 10 min
    private static final long HEARTBEAT_MS   = 25_000L;

    /**
     * Per-viewer concurrent-emitter cap. Story tray is a logged-in
     * background stream — even a power user with phone + laptop + desk only
     * needs a handful. Beyond the cap the oldest emitter is closed (LRU)
     * so a runaway tab loop can't accumulate connections.
     */
    private static final int  MAX_EMITTERS_PER_USER = 5;

    /** viewerId → list of open SSE emitters (multiple tabs) */
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> sessions = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        CopyOnWriteArrayList<SseEmitter> bucket =
                sessions.computeIfAbsent(viewerId, id -> new CopyOnWriteArrayList<>());

        // Evict oldest emitters until under the cap. complete() triggers the
        // emitter's own onCompletion which removes it from this bucket; the
        // explicit remove() is defensive in case the emitter already finished.
        while (bucket.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = bucket.get(0);
            log.info("[TRAY-SSE] User {} exceeded {} concurrent connections — closing oldest",
                    viewerId, MAX_EMITTERS_PER_USER);
            try { oldest.complete(); } catch (Exception ignore) { /* best-effort */ }
            bucket.remove(oldest);
        }

        bucket.add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = sessions.get(viewerId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) sessions.remove(viewerId);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data("{\"viewerId\":\"" + viewerId + "\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        log.debug("[TRAY-SSE] User [{}] subscribed to story tray stream", viewerId);
        return emitter;
    }

    /** Called by StoryTrayRealtimeSubscriber — fans out to all local emitters for this viewer. */
    public void deliver(UUID viewerId, StoryTrayEvent event) {
        List<SseEmitter> emitters = sessions.get(viewerId);
        if (emitters == null || emitters.isEmpty()) return;

        String eventName = event.getEventType().name().toLowerCase();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(event));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * One shared heartbeat tick for every open tray-stream emitter.
     * Replaces the previous per-connection {@code newSingleThreadScheduledExecutor}
     * which leaked a JVM thread per SSE client (10k users → 10k threads → OOM).
     * Dead emitters are removed inline so a single bad connection can't accumulate.
     */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        if (sessions.isEmpty()) return;
        sessions.forEach((viewerId, bucket) -> {
            for (SseEmitter emitter : bucket) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                } catch (Exception e) {
                    bucket.remove(emitter);
                    if (bucket.isEmpty()) sessions.remove(viewerId, bucket);
                }
            }
        });
    }
}
