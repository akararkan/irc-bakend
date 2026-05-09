package ak.dev.irc.app.rabbitmq.event.qna;

import java.time.LocalDateTime;
import java.util.UUID;

public record BestAnswerVotedEvent(
        String eventId,
        LocalDateTime occurredAt,
        UUID questionId,
        String questionTitle,
        UUID answerId,
        UUID answerAuthorId,
        String answerAuthorUsername,
        String answerAuthorFullName,
        UUID voterId,
        String voterUsername,
        String voterFullName,
        long bestAnswerVoteCount,
        boolean voted
) {
    public static BestAnswerVotedEvent of(UUID questionId, String questionTitle,
                                          UUID answerId,
                                          UUID answerAuthorId, String answerAuthorUsername, String answerAuthorFullName,
                                          UUID voterId, String voterUsername, String voterFullName,
                                          long bestAnswerVoteCount, boolean voted) {
        return new BestAnswerVotedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                questionId,
                questionTitle,
                answerId,
                answerAuthorId,
                answerAuthorUsername,
                answerAuthorFullName,
                voterId,
                voterUsername,
                voterFullName,
                bestAnswerVoteCount,
                voted
        );
    }
}
