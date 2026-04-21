package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionCreatedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        String questionBodyPreview,
        UUID authorId,
        String authorUsername,
        String authorFullName
) {
    public static QuestionCreatedEvent of(UUID questionId, String questionTitle, String questionBodyPreview,
                                          UUID authorId, String authorUsername, String authorFullName) {
        return new QuestionCreatedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                questionBodyPreview,
                authorId,
                authorUsername,
                authorFullName
        );
    }
}