package ak.dev.irc.app.chat.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.UUID;

/**
 * Fans a {@link ChatRealtimeEvent} out to a set of recipients, deferring the
 * publish until <b>after</b> the surrounding DB transaction commits — so a
 * subscriber can never observe a message (or counter delta) that a rollback
 * would erase. Mirrors the platform's {@code PostRealtimeBroadcaster} pattern.
 *
 * <p>Membership authorization lives with the caller: the recipient set is the
 * resolved conversation participants. The SSE layer only checks identity
 * ("is this my userId"), never room membership.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRealtimeBroadcaster {

    private final ChatRedisPublisher publisher;

    /** Deliver to every recipient (typically all conversation members). */
    public void broadcast(Collection<UUID> recipientIds, ChatRealtimeEvent event) {
        if (recipientIds == null || recipientIds.isEmpty()) return;
        runAfterCommit(() -> {
            for (UUID recipientId : recipientIds) {
                publisher.publish(recipientId, event);
            }
        });
    }

    /** Deliver to everyone except {@code excludeUserId} (e.g. the actor). */
    public void broadcastExcept(Collection<UUID> recipientIds, UUID excludeUserId, ChatRealtimeEvent event) {
        if (recipientIds == null || recipientIds.isEmpty()) return;
        runAfterCommit(() -> {
            for (UUID recipientId : recipientIds) {
                if (recipientId.equals(excludeUserId)) continue;
                publisher.publish(recipientId, event);
            }
        });
    }

    /** Deliver to a single recipient. */
    public void broadcastTo(UUID recipientId, ChatRealtimeEvent event) {
        if (recipientId == null) return;
        runAfterCommit(() -> publisher.publish(recipientId, event));
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { action.run(); } catch (Exception e) {
                        log.warn("[CHAT-BROADCAST] post-commit publish failed: {}", e.getMessage());
                    }
                }
            });
        } else {
            try { action.run(); } catch (Exception e) {
                log.warn("[CHAT-BROADCAST] publish failed: {}", e.getMessage());
            }
        }
    }
}
