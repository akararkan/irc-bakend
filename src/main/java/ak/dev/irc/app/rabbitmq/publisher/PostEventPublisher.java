package ak.dev.irc.app.rabbitmq.publisher;


import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import ak.dev.irc.app.rabbitmq.event.post.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishPostCreated(PostCreatedEvent event) {
        publish(RabbitMQConstants.POST_CREATED, event,
                "POST_CREATED postId=" + event.getPostId());
    }

    public void publishPostReacted(PostReactedEvent event) {
        publish(RabbitMQConstants.POST_REACTED, event,
                "POST_REACTED postId=" + event.getPostId() + " type=" + event.getReactionType());
    }

    public void publishPostCommented(PostCommentedEvent event) {
        publish(RabbitMQConstants.POST_COMMENTED, event,
                "POST_COMMENTED postId=" + event.getPostId() + " isReply=" + event.isReply());
    }

    public void publishCommentReacted(PostCommentReactedEvent event) {
        publish(RabbitMQConstants.POST_COMMENT_REACTED, event,
                "POST_COMMENT_REACTED commentId=" + event.getCommentId());
    }

    public void publishPostShared(PostSharedEvent event) {
        publish(RabbitMQConstants.POST_SHARED, event,
                "POST_SHARED postId=" + event.getPostId() + " sharerId=" + event.getSharerId());
    }

    private void publish(String routingKey, Object event, String label) {
        Runnable publishAction = () -> {
            try {
                rabbitTemplate.convertAndSend(RabbitMQConstants.IRC_EXCHANGE, routingKey, event);
                log.info("[RabbitMQ] Published → {}", label);
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
}