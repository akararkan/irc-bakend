package ak.dev.irc.app.rabbitmq.event.user;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when User A blocks User B.
 * Consumer → no notification (blocks are silent by design),
 * but the event is available for audit / analytics consumers.
 */
public record UserBlockedEvent(
        String        eventId,
        LocalDateTime occurredAt,
        UUID          actorId,
        UUID          targetId
) {
    public static UserBlockedEvent of(UUID actorId, UUID targetId) {
        return new UserBlockedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                actorId, targetId
        );
    }
}
