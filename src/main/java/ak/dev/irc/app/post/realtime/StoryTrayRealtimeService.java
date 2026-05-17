package ak.dev.irc.app.post.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private static final long SSE_TIMEOUT_MS    = 10 * 60 * 1_000L; // 10 min
    private static final long HEARTBEAT_DELAY_S = 25L;

    /** viewerId → list of open SSE emitters (multiple tabs) */
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> sessions = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sessions.computeIfAbsent(viewerId, id -> new CopyOnWriteArrayList<>()).add(emitter);

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

        scheduleHeartbeat(emitter);
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

    private void scheduleHeartbeat(SseEmitter emitter) {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, HEARTBEAT_DELAY_S, HEARTBEAT_DELAY_S, TimeUnit.SECONDS);
    }
}
