package ak.dev.irc.app.rabbitmq.event.user;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when User A follows User B.
 * Consumer → creates a NEW_FOLLOWER notification for the target.
 */
public record UserFollowedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        /** The user who pressed "follow" */
        UUID   actorId,
        String actorUsername,
        String actorFullName,

        /** The user who gained a new follower */
        UUID   targetId
) {
    public static UserFollowedEvent of(UUID actorId, String actorUsername,
                                       String actorFullName, UUID targetId) {
        return new UserFollowedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                actorId, actorUsername, actorFullName, targetId
        );
    }
}
