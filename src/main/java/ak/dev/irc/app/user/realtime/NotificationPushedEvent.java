package ak.dev.irc.app.user.realtime;

import ak.dev.irc.app.user.dto.response.NotificationResponse;

import java.util.UUID;

/**
 * Spring application event fired (within a transaction) when a Notification row is saved.
 * A {@code @TransactionalEventListener(AFTER_COMMIT)} picks this up and pushes the
 * payload to Redis pub/sub, which then fans out to the SSE emitter for that user.
 *
 * <p>Decoupling the push via an after-commit listener ensures that if the DB transaction
 * rolls back, no spurious SSE events are sent to the client.</p>
 */
public record NotificationPushedEvent(UUID recipientId, NotificationResponse payload) {}

