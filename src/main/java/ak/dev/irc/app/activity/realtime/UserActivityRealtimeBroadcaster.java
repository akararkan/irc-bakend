package ak.dev.irc.app.activity.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * High-level broadcaster used by services. Defers the publish until
 * {@code afterCommit} when invoked inside a transaction so a subscriber
 * never sees an activity row that the database is about to roll back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityRealtimeBroadcaster {

    private final UserActivityRealtimePublisher publisher;

    public void broadcast(UUID userId, UserActivityRealtimeEvent event) {
        if (userId == null || event == null) return;
        Runnable action = () -> publisher.publish(userId, event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
            return;
        }
        action.run();
    }
}
