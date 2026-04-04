package ak.dev.irc.app.user.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the JPA transaction lifecycle and the real-time push pipeline.
 *
 * <p>Only after the DB transaction commits successfully does this listener
 * publish the notification to Redis → SSE.  This avoids sending SSE events
 * for notifications that were never actually persisted.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushEventListener {

    private final NotificationRedisPublisher redisPublisher;

    /**
     * Runs after the enclosing transaction commits.
     * Annotated {@code @Async} so it never blocks the committing thread.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationPushed(NotificationPushedEvent event) {
        log.debug("[PUSH] Publishing SSE notification for user={} after TX commit", event.recipientId());
        redisPublisher.publish(event.recipientId(), event.payload());
    }
}

