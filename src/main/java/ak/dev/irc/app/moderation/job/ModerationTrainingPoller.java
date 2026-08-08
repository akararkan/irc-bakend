package ak.dev.irc.app.moderation.job;

import ak.dev.irc.app.admin.ops.JobPauseRegistry;
import ak.dev.irc.app.moderation.service.ModerationTrainingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chases in-flight fine-tuning jobs (MODERATION_ROADMAP.md §12.4).
 *
 * <p>The training container also POSTs a completion webhook, but a webhook is
 * exactly the kind of thing that quietly fails — a wrong
 * {@code host.docker.internal}, a restarted backend, a firewall. Polling costs
 * one HTTP call every fifteen seconds only while a job is actually running, and
 * it means a retrain never sits at "training" forever because one packet went
 * missing. Whichever signal lands first settles the row; the other is a no-op.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationTrainingPoller {

    private static final String JOB = "moderation-training-poll";

    private final ModerationTrainingService trainingService;
    private final JobPauseRegistry jobPause;

    @Scheduled(initialDelayString = "${app.moderation.retrain.poll-initial-delay-ms:30000}",
            fixedDelayString = "${app.moderation.retrain.poll-interval-ms:15000}")
    public void poll() {
        if (jobPause.isPaused(JOB)) return;
        try {
            int settled = trainingService.pollRunningJobs();
            if (settled > 0) {
                log.info("[MODERATION-TRAIN] {} job(s) reached a terminal state", settled);
            }
        } catch (Exception ex) {
            // The training container being down is the normal case — it only runs
            // on demand. Debug, not warn.
            log.debug("[MODERATION-TRAIN] poll skipped: {}", ex.getMessage());
        }
    }
}
