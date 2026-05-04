package ak.dev.irc.app.rabbitmq.publisher;

import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import ak.dev.irc.app.rabbitmq.event.user.UserMentionedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link UserMentionedEvent} after the surrounding DB transaction
 * commits — same after-commit pattern as {@code PostEventPublisher}. If no
 * transaction is active (rare; mostly tests) we publish synchronously.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserMentionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(UserMentionedEvent event) {
        int directCount = event.getMentionedUserIds() == null ? 0 : event.getMentionedUserIds().size();
        String label = "USER_MENTIONED source=" + event.getSourceType()
                + " sourceId=" + event.getSourceId()
                + " direct=" + directCount
                + " followers=" + event.isNotifyFollowers();

        Runnable publishAction = () -> {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConstants.IRC_EXCHANGE,
                        RabbitMQConstants.USER_MENTIONED,
                        event);
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
