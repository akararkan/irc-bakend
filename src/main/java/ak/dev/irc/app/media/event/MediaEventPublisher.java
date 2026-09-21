package ak.dev.irc.app.media.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.IRC_EXCHANGE;
import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MEDIA_DELETE_REQUESTED;
import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MEDIA_PROCESS_REQUESTED;

/**
 * Publishes media pipeline events, following the {@code ModerationEventPublisher}
 * convention: never throws, and defers to {@code afterCommit} when a transaction
 * is active so no message is emitted for an asset row that never landed.
 *
 * <p>A publish failure is logged, not propagated — the asset row stays
 * PROCESSING and {@code MediaStuckSweeper} republishes from the database. The
 * queue is the fast path, not the only path.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishProcessRequested(UUID assetId, String reason) {
        MediaProcessRequestedEvent event = MediaProcessRequestedEvent.builder()
                .assetId(assetId)
                .reason(reason)
                .build();
        afterCommitOrNow(() -> {
            try {
                rabbitTemplate.convertAndSend(IRC_EXCHANGE, MEDIA_PROCESS_REQUESTED, event);
                log.debug("[RabbitMQ] Published → media.process.requested asset={} ({})", assetId, reason);
            } catch (Exception ex) {
                log.warn("[MEDIA] could not enqueue processing for {} — the sweeper will republish: {}",
                        assetId, ex.getMessage());
            }
        });
    }

    public void publishDeleteAsset(UUID assetId) {
        publishDelete(MediaDeleteRequestedEvent.builder().assetId(assetId).build());
    }

    public void publishDeleteLegacyKey(String legacyKey) {
        if (legacyKey == null || legacyKey.isBlank()) return;
        publishDelete(MediaDeleteRequestedEvent.builder().legacyKey(legacyKey).build());
    }

    private void publishDelete(MediaDeleteRequestedEvent event) {
        afterCommitOrNow(() -> {
            try {
                rabbitTemplate.convertAndSend(IRC_EXCHANGE, MEDIA_DELETE_REQUESTED, event);
                log.debug("[RabbitMQ] Published → media.delete.requested asset={} legacyKey={}",
                        event.getAssetId(), event.getLegacyKey());
            } catch (Exception ex) {
                // A missed delete is an orphaned object — the reconcile job's territory.
                log.warn("[MEDIA] could not enqueue delete (asset={} legacyKey={}): {}",
                        event.getAssetId(), event.getLegacyKey(), ex.getMessage());
            }
        });
    }

    private void afterCommitOrNow(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
