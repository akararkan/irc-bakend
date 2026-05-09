package ak.dev.irc.app.rabbitmq.event.post;

import java.time.LocalDateTime;
import java.util.UUID;

public record PostDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID postId,
        UUID actorId
) {
    public static PostDeletedEvent of(UUID postId, UUID actorId) {
        return new PostDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                postId,
                actorId
        );
    }
}
