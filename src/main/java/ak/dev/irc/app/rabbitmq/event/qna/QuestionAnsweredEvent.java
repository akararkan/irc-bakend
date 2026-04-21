package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionAnsweredEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        UUID questionAuthorId,
        String questionAuthorUsername,
        String questionAuthorFullName,
        UUID answerId,
        String answerBodyPreview,
        UUID answerAuthorId,
        String answerAuthorUsername,
        String answerAuthorFullName
) {
    public static QuestionAnsweredEvent of(UUID questionId, String questionTitle,
                                           UUID questionAuthorId, String questionAuthorUsername,
                                           String questionAuthorFullName,
                                           UUID answerId, String answerBodyPreview,
                                           UUID answerAuthorId, String answerAuthorUsername,
                                           String answerAuthorFullName) {
        return new QuestionAnsweredEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                questionAuthorId,
                questionAuthorUsername,
                questionAuthorFullName,
                answerId,
                answerBodyPreview,
                answerAuthorId,
                answerAuthorUsername,
                answerAuthorFullName
        );
    }
}