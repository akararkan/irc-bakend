package ak.dev.irc.app.rabbitmq.publisher;

import ak.dev.irc.app.qna.entity.AnswerFeedback;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerAcceptedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerDeletedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerFeedbackAddedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerReactedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.AnswerUnreactedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.BestAnswerVotedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionAnsweredEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionCreatedEvent;
import ak.dev.irc.app.rabbitmq.event.qna.QuestionDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishQuestionCreated(Question question) {
        QuestionCreatedEvent event = QuestionCreatedEvent.of(
                question.getId(),
                question.getTitle(),
                preview(question.getBody()),
                question.getAuthor().getId(),
                question.getAuthor().getUsername(),
                question.getAuthor().getFullName()
        );

        publish(RabbitMQConstants.QNA_QUESTION_CREATED, event,
                "QUESTION_CREATED questionId=" + question.getId());
    }

    public void publishQuestionAnswered(Question question, QuestionAnswer answer) {
        QuestionAnsweredEvent event = QuestionAnsweredEvent.of(
                question.getId(),
                question.getTitle(),
                question.getAuthor().getId(),
                question.getAuthor().getUsername(),
                question.getAuthor().getFullName(),
                answer.getId(),
                preview(answer.getBody()),
                answer.getAuthor().getId(),
                answer.getAuthor().getUsername(),
                answer.getAuthor().getFullName()
        );

        publish(RabbitMQConstants.QNA_QUESTION_ANSWERED, event,
                "QUESTION_ANSWERED questionId=" + question.getId() + " answerId=" + answer.getId());
    }

    public void publishAnswerAccepted(Question question, QuestionAnswer answer) {
        AnswerAcceptedEvent event = AnswerAcceptedEvent.of(
                question.getId(),
                question.getTitle(),
                question.getAuthor().getId(),
                question.getAuthor().getUsername(),
                question.getAuthor().getFullName(),
                answer.getId(),
                answer.getAuthor().getId(),
                answer.getAuthor().getUsername(),
                answer.getAuthor().getFullName()
        );

        publish(RabbitMQConstants.QNA_ANSWER_ACCEPTED, event,
                "ANSWER_ACCEPTED questionId=" + question.getId() + " answerId=" + answer.getId());
    }

    public void publishAnswerReacted(Question question, QuestionAnswer answer, User reactor, String reactionType) {
        AnswerReactedEvent event = AnswerReactedEvent.of(
                question.getId(),
                question.getTitle(),
                answer.getId(),
                answer.getAuthor().getId(),
                answer.getAuthor().getUsername(),
                answer.getAuthor().getFullName(),
                reactor.getId(),
                reactor.getUsername(),
                reactor.getFullName(),
                reactionType,
                answer.getParentAnswer() != null
        );

        publish(RabbitMQConstants.QNA_ANSWER_REACTED, event,
                "ANSWER_REACTED questionId=" + question.getId() + " answerId=" + answer.getId());
    }

    public void publishFeedbackAdded(Question question, QuestionAnswer answer, AnswerFeedback feedback) {
        AnswerFeedbackAddedEvent event = AnswerFeedbackAddedEvent.of(
                question.getId(),
                question.getTitle(),
                question.getAuthor().getId(),
                question.getAuthor().getFullName(),
                answer.getId(),
                answer.getAuthor().getId(),
                answer.getAuthor().getUsername(),
                answer.getAuthor().getFullName(),
                feedback.getFeedbackType().name(),
                preview(feedback.getBody())
        );

        publish(RabbitMQConstants.QNA_FEEDBACK_ADDED, event,
                "FEEDBACK_ADDED questionId=" + question.getId() + " answerId=" + answer.getId());
    }

    public void publishQuestionDeleted(java.util.UUID questionId, java.util.UUID actorId) {
        publish(RabbitMQConstants.QNA_QUESTION_DELETED,
                QuestionDeletedEvent.of(questionId, actorId),
                "QUESTION_DELETED questionId=" + questionId);
    }

    public void publishAnswerDeleted(java.util.UUID questionId, java.util.UUID answerId,
                                      java.util.UUID parentAnswerId, java.util.UUID actorId) {
        publish(RabbitMQConstants.QNA_ANSWER_DELETED,
                AnswerDeletedEvent.of(questionId, answerId, parentAnswerId, actorId),
                "ANSWER_DELETED questionId=" + questionId + " answerId=" + answerId);
    }

    public void publishAnswerUnreacted(java.util.UUID questionId, java.util.UUID answerId,
                                        java.util.UUID actorId, String previousReactionType) {
        publish(RabbitMQConstants.QNA_ANSWER_UNREACTED,
                AnswerUnreactedEvent.of(questionId, answerId, actorId, previousReactionType),
                "ANSWER_UNREACTED questionId=" + questionId + " answerId=" + answerId);
    }

    public void publishBestAnswerVoted(Question question, QuestionAnswer answer, User voter,
                                        long bestAnswerVoteCount, boolean voted) {
        BestAnswerVotedEvent event = BestAnswerVotedEvent.of(
                question.getId(),
                question.getTitle(),
                answer.getId(),
                answer.getAuthor().getId(),
                answer.getAuthor().getUsername(),
                answer.getAuthor().getFullName(),
                voter.getId(),
                voter.getUsername(),
                voter.getFullName(),
                bestAnswerVoteCount,
                voted
        );
        String routingKey = voted
                ? RabbitMQConstants.QNA_BEST_ANSWER_VOTED
                : RabbitMQConstants.QNA_BEST_ANSWER_UNVOTED;
        publish(routingKey, event,
                (voted ? "BEST_ANSWER_VOTED" : "BEST_ANSWER_UNVOTED")
                        + " questionId=" + question.getId() + " answerId=" + answer.getId());
    }

    private void publish(String routingKey, Object event, String label) {
        Runnable publishAction = () -> {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConstants.IRC_EXCHANGE, routingKey, event);
                log.info("[RabbitMQ] Published -> {}", label);
            } catch (Exception e) {
                log.error("[RabbitMQ] Failed to publish {} : {}", label, e.getMessage(), e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAction.run();
                }
            });
            return;
        }

        publishAction.run();
    }

    private String preview(String text) {
        if (text == null) {
            return null;
        }

        String trimmed = text.trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }
}
