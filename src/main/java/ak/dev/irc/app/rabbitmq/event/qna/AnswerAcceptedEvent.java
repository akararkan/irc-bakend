package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerAcceptedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        UUID questionAuthorId,
        String questionAuthorUsername,
        String questionAuthorFullName,
        UUID answerId,
        UUID answerAuthorId,
        String answerAuthorUsername,
        String answerAuthorFullName
) {
    public static AnswerAcceptedEvent of(UUID questionId, String questionTitle,
                                         UUID questionAuthorId, String questionAuthorUsername,
                                         String questionAuthorFullName,
                                         UUID answerId,
                                         UUID answerAuthorId, String answerAuthorUsername,
                                         String answerAuthorFullName) {
        return new AnswerAcceptedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                questionAuthorId,
                questionAuthorUsername,
                questionAuthorFullName,
                answerId,
                answerAuthorId,
                answerAuthorUsername,
                answerAuthorFullName
        );
    }
}
