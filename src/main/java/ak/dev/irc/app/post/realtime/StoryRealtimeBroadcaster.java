package ak.dev.irc.app.post.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Defers story real-time broadcasts until afterCommit when called inside a
 * transaction — same pattern as PostRealtimeBroadcaster — so SSE subscribers
 * never receive stale counters that the DB is about to roll back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryRealtimeBroadcaster {

    private final StoryRealtimePublisher publisher;

    public void broadcast(UUID storyId, StoryRealtimeEvent event) {
        if (storyId == null || event == null) return;

        Runnable action = () -> publisher.publish(storyId, event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { action.run(); }
                });
            return;
        }
        action.run();
    }
}
