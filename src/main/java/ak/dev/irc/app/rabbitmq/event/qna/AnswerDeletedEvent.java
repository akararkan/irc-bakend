package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        UUID answerId,
        UUID parentAnswerId,
        UUID actorId
) {
    public static AnswerDeletedEvent of(UUID questionId, UUID answerId, UUID parentAnswerId, UUID actorId) {
        return new AnswerDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                answerId,
                parentAnswerId,
                actorId
        );
    }
}
