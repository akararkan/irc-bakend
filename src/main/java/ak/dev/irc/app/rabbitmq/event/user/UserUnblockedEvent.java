package ak.dev.irc.app.rabbitmq.event.user;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when User A unblocks User B.
 * Consumer → creates an UNBLOCKED notification for the target.
 */
public record UserUnblockedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        /** The user who lifted the block */
        UUID   actorId,
        String actorUsername,
        String actorFullName,

        /** The user who was unblocked */
        UUID   targetId
) {
    public static UserUnblockedEvent of(UUID actorId, String actorUsername,
                                        String actorFullName, UUID targetId) {
        return new UserUnblockedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                actorId, actorUsername, actorFullName, targetId
        );
    }
}
