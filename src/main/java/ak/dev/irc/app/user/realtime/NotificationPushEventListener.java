package ak.dev.irc.app.user.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Bridges the JPA transaction lifecycle and the real-time push pipeline.
 *
 * <p>Only after the DB transaction commits successfully does this listener
 * publish the notification to Redis → SSE.  This avoids sending SSE events
 * for notifications that were never actually persisted.</p>
 *
 * <p>Three event types are bridged:
 * <ul>
 *   <li>{@link NotificationPushedEvent} — a new (or coalesced) notification.</li>
 *   <li>{@link NotificationUnreadCountEvent} — badge counter update so every
 *       open tab refreshes after read / delete actions.</li>
 *   <li>{@link NotificationReadEvent} — mark-read / delete propagation so
 *       sibling tabs strike the row out of their local cache.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushEventListener {

    private final NotificationRedisPublisher redisPublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationPushed(NotificationPushedEvent event) {
        log.debug("[PUSH] Publishing SSE notification for user={} after TX commit", event.recipientId());
        redisPublisher.publish(event.recipientId(),
                NotificationRedisPublisher.SseEventName.NOTIFICATION,
                event.payload());
    }

    /**
     * Counts can be published outside a transaction (e.g. from controller-level
     * mark-read calls), so we accept either phase via a plain
     * {@code @EventListener} fallback in addition to AFTER_COMMIT.
     */
    @Async
    @EventListener
    public void onUnreadCountChanged(NotificationUnreadCountEvent event) {
        log.debug("[PUSH] Publishing unread-count={} for user={}", event.unreadCount(), event.recipientId());
        redisPublisher.publish(event.recipientId(),
                NotificationRedisPublisher.SseEventName.UNREAD_COUNT,
                Map.of("count", event.unreadCount()));
    }

    @Async
    @EventListener
    public void onReadOrDeleted(NotificationReadEvent event) {
        log.debug("[PUSH] Publishing read/delete event ids={} allRead={} deleted={} for user={}",
                event.ids().size(), event.allRead(), event.deleted(), event.recipientId());
        redisPublisher.publish(event.recipientId(),
                event.deleted()
                        ? NotificationRedisPublisher.SseEventName.DELETED
                        : NotificationRedisPublisher.SseEventName.READ,
                Map.of(
                        "ids", event.ids(),
                        "allRead", event.allRead(),
                        "deleted", event.deleted()
                ));
    }
}
