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

    public void publishPostDeleted(java.util.UUID postId, java.util.UUID actorId) {
        publish(RabbitMQConstants.POST_DELETED,
                PostDeletedEvent.of(postId, actorId),
                "POST_DELETED postId=" + postId);
    }

    public void publishPostUnreacted(java.util.UUID postId, java.util.UUID actorId, String previousReactionType) {
        publish(RabbitMQConstants.POST_UNREACTED,
                PostUnreactedEvent.of(postId, actorId, previousReactionType),
                "POST_UNREACTED postId=" + postId);
    }

    public void publishPostCommentDeleted(java.util.UUID postId, java.util.UUID commentId,
                                           java.util.UUID parentCommentId, java.util.UUID actorId) {
        publish(RabbitMQConstants.POST_COMMENT_DELETED,
                PostCommentDeletedEvent.of(postId, commentId, parentCommentId, actorId),
                "POST_COMMENT_DELETED postId=" + postId + " commentId=" + commentId);
    }

    /** Wires the (previously dead) sound-approval event so uploaders get notified. */
    public void publishSoundApproved(ak.dev.irc.app.rabbitmq.event.post.SoundApprovedEvent event) {
        publish(RabbitMQConstants.SOUND_APPROVED, event,
                "SOUND_APPROVED soundId=" + event.getSoundId());
    }

    public void publishSoundRejected(ak.dev.irc.app.rabbitmq.event.post.SoundRejectedEvent event) {
        publish(RabbitMQConstants.SOUND_REJECTED, event,
                "SOUND_REJECTED soundId=" + event.getSoundId());
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