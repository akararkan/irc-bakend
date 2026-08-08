package ak.dev.irc.app.moderation.worker;

import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MODERATION_QUEUE;

/**
 * The Moderation Worker of MODERATION_ROADMAP.md §11: drains
 * {@code irc.queue.moderation} and re-drives cases the inline attempt could not
 * decide.
 *
 * <p>Deliberately swallows nothing. The container's retry advice gives three
 * attempts with exponential back-off and then dead-letters, and a dead-lettered
 * moderation message is <em>fine</em> — the case row is still {@code PENDING} in
 * Postgres and the SLA sweeper applies the configured fallback policy on the
 * deadline. Letting the exception propagate is what makes that back-off happen;
 * catching it here would burn all three attempts inside a few milliseconds.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationWorker {

    private final ContentModerationService moderationService;

    @RabbitListener(queues = MODERATION_QUEUE)
    public void onModerationRequested(ModerationRequestedEvent event) {
        if (event == null || event.getCaseId() == null) return;

        ModerationStatus status = moderationService.rescore(event.getCaseId());
        if (status == ModerationStatus.PENDING) {
            // Still undecided: throw so the listener's back-off retries rather
            // than acking a case nobody will look at again until the deadline.
            throw new IllegalStateException(
                    "moderation case %s still undecided — inference unavailable"
                            .formatted(event.getCaseId()));
        }
        log.debug("[MODERATION] worker settled case {} → {}", event.getCaseId(), status);
    }
}
