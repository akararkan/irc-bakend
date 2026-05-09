package ak.dev.irc.app.rabbitmq.event.post;

import java.time.LocalDateTime;
import java.util.UUID;

public record PostUnreactedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID postId,
        UUID actorId,
        String previousReactionType
) {
    public static PostUnreactedEvent of(UUID postId, UUID actorId, String previousReactionType) {
        return new PostUnreactedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                postId,
                actorId,
                previousReactionType
        );
    }
}
