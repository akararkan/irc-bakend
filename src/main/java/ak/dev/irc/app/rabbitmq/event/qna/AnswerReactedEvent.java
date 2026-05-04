package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a user reacts to (or changes their reaction on) a question answer
 * or reanswer. Routed under {@code qna.social.answer.reacted} so the
 * notification consumer can persist a notification for the answer author and
 * record activity for the reactor.
 */
public record AnswerReactedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        UUID answerId,
        UUID answerAuthorId,
        String answerAuthorUsername,
        String answerAuthorFullName,
        UUID reactorId,
        String reactorUsername,
        String reactorFullName,
        String reactionType,
        boolean reanswer
) {
    public static AnswerReactedEvent of(UUID questionId, String questionTitle,
                                         UUID answerId,
                                         UUID answerAuthorId, String answerAuthorUsername,
                                         String answerAuthorFullName,
                                         UUID reactorId, String reactorUsername,
                                         String reactorFullName,
                                         String reactionType, boolean reanswer) {
        return new AnswerReactedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                answerId,
                answerAuthorId,
                answerAuthorUsername,
                answerAuthorFullName,
                reactorId,
                reactorUsername,
                reactorFullName,
                reactionType,
                reanswer
        );
    }
}
