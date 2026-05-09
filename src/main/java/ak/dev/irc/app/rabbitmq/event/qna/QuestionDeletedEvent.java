package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        UUID actorId
) {
    public static QuestionDeletedEvent of(UUID questionId, UUID actorId) {
        return new QuestionDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                actorId
        );
    }
}
