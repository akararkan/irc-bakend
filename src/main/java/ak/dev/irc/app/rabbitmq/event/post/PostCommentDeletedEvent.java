package ak.dev.irc.app.rabbitmq.event.post;

import java.time.LocalDateTime;
import java.util.UUID;

public record PostCommentDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID postId,
        UUID commentId,
        UUID parentCommentId,
        UUID actorId
) {
    public static PostCommentDeletedEvent of(UUID postId, UUID commentId, UUID parentCommentId, UUID actorId) {
        return new PostCommentDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                postId,
                commentId,
                parentCommentId,
                actorId
        );
    }
}
