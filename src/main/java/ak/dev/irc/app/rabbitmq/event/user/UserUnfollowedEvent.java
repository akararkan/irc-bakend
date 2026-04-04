package ak.dev.irc.app.rabbitmq.event.user;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when User A unfollows User B.
 * Consumer → removes the earlier NEW_FOLLOWER notification.
 */
public record UserUnfollowedEvent(
        String        eventId,
        LocalDateTime occurredAt,
        UUID          actorId,
        UUID          targetId
) {
    public static UserUnfollowedEvent of(UUID actorId, UUID targetId) {
        return new UserUnfollowedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                actorId, targetId
        );
    }
}
