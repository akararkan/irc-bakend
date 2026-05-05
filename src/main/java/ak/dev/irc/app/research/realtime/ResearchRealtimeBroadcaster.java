package ak.dev.irc.app.research.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * High-level API services call to broadcast a {@link ResearchRealtimeEvent}.
 * Defers to {@code afterCommit} when a transaction is active so subscribers
 * never see counters that the database is about to roll back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchRealtimeBroadcaster {

    private final ResearchRealtimePublisher publisher;

    public void broadcast(ResearchRealtimeEvent event) {
        if (event == null || event.getResearchId() == null) return;

        Runnable action = () -> publisher.publish(event.getResearchId(), event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
            return;
        }
        action.run();
    }
}
