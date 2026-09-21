package ak.dev.irc.app.media.worker;

import ak.dev.irc.app.media.event.MediaDeleteRequestedEvent;
import ak.dev.irc.app.media.service.MediaDeleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MEDIA_DELETE_QUEUE;

/**
 * Consumes {@code irc.queue.media.delete}: async removal of an asset's objects
 * and rows (or one legacy pre-pipeline key), so domain deletes never pay N
 * storage round-trips inline. The heavy lifting — including dedup-safe object
 * handling — lives in {@link MediaDeleteService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaDeleteWorker {

    private final MediaDeleteService deleteService;

    @RabbitListener(queues = MEDIA_DELETE_QUEUE)
    public void onDeleteRequested(MediaDeleteRequestedEvent event) {
        if (event.getLegacyKey() != null && !event.getLegacyKey().isBlank()) {
            deleteService.deleteLegacyKey(event.getLegacyKey());
            return;
        }
        if (event.getAssetId() != null) {
            deleteService.deleteAssetNow(event.getAssetId());
        }
    }
}
