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

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryRealtimeService {

    private static final long SSE_TIMEOUT_MS   = 5 * 60 * 1_000L;
    private static final long HEARTBEAT_DELAY_S = 25L;

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> topics = new ConcurrentHashMap<>();
    private final StoryRealtimePublisher   publisher;
    private final StoryRealtimeBroadcaster broadcaster;

    public SseEmitter subscribe(UUID storyId, UUID viewerId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        topics.computeIfAbsent(storyId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = topics.get(storyId);
            if (list != null) { list.remove(emitter); if (list.isEmpty()) topics.remove(storyId); }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"storyId\":\"" + storyId + "\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        scheduleHeartbeat(emitter, storyId);
        return emitter;
    }

    public void broadcast(UUID storyId, StoryRealtimeEvent event) {
        List<SseEmitter> emitters = topics.get(storyId);
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

    /** Called by services — defers until afterCommit when inside a transaction. */
    public void broadcastEvent(UUID storyId, StoryRealtimeEvent event) {
        broadcaster.broadcast(storyId, event);
    }

    private void scheduleHeartbeat(SseEmitter emitter, UUID storyId) {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, HEARTBEAT_DELAY_S, HEARTBEAT_DELAY_S, TimeUnit.SECONDS);
    }
}
