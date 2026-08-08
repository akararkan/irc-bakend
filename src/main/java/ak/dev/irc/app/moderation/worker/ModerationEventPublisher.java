package ak.dev.irc.app.moderation.worker;

import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.IRC_EXCHANGE;
import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MODERATION_REQUESTED;

/**
 * Publishes {@link ModerationRequestedEvent} onto the topic exchange, following
 * the {@code PostEventPublisher} convention: never throws, and defers to
 * {@code afterCommit} when a transaction is active so no message is emitted for
 * a case row that never landed.
 *
 * <p>A publish failure is logged, not propagated. The SLA sweeper polls the
 * database directly and will pick the case up regardless — the queue is the fast
 * path, not the only path (§11).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishModerationRequested(UUID caseId, ModeratedEntityType type, String entityRef) {
        ModerationRequestedEvent event = ModerationRequestedEvent.builder()
                .caseId(caseId)
                .entityType(type.name())
                .entityRef(entityRef)
                .build();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(ModerationRequestedEvent event) {
        try {
            rabbitTemplate.convertAndSend(IRC_EXCHANGE, MODERATION_REQUESTED, event);
            log.debug("[RabbitMQ] Published → moderation.requested case={} {}",
                    event.getCaseId(), event.getEntityRef());
        } catch (Exception ex) {
            log.warn("[MODERATION] could not enqueue case {} — the SLA sweeper will pick it up: {}",
                    event.getCaseId(), ex.getMessage());
        }
    }
}
