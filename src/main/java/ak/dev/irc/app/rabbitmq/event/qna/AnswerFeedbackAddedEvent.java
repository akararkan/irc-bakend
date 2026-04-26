package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerFeedbackAddedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        UUID questionAuthorId,
        String questionAuthorFullName,
        UUID answerId,
        UUID answerAuthorId,
        String answerAuthorUsername,
        String answerAuthorFullName,
        String feedbackType,
        String feedbackBodyPreview
) {
    public static AnswerFeedbackAddedEvent of(UUID questionId, String questionTitle,
                                               UUID questionAuthorId, String questionAuthorFullName,
                                               UUID answerId,
                                               UUID answerAuthorId, String answerAuthorUsername,
                                               String answerAuthorFullName,
                                               String feedbackType, String feedbackBodyPreview) {
        return new AnswerFeedbackAddedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                questionAuthorId,
                questionAuthorFullName,
                answerId,
                answerAuthorId,
                answerAuthorUsername,
                answerAuthorFullName,
                feedbackType,
                feedbackBodyPreview
        );
    }
}
