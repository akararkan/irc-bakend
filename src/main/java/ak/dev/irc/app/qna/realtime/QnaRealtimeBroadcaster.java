package ak.dev.irc.app.qna.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * High-level API services call to broadcast a {@link QnaRealtimeEvent}.
 *
 * <p>If a transaction is active the broadcast is deferred until {@code afterCommit}
 * so subscribers never see counters that the database is about to roll back.
 * Outside a transaction it fires immediately.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnaRealtimeBroadcaster {

    private final QnaRealtimePublisher publisher;

    public void broadcast(QnaRealtimeEvent event) {
        if (event == null || event.getQuestionId() == null) return;

        Runnable action = () -> publisher.publish(event.getQuestionId(), event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
            return;
        }
        action.run();
    }
}
