package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerUnreactedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        UUID answerId,
        UUID actorId,
        String previousReactionType
) {
    public static AnswerUnreactedEvent of(UUID questionId, UUID answerId, UUID actorId, String previousReactionType) {
        return new AnswerUnreactedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                answerId,
                actorId,
                previousReactionType
        );
    }
}
