package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Daily prune of read notifications so the inbox table stays bounded.
 *
 * <p>Runs at 03:15 server-local every day (off-peak). Anything that has been
 * read for more than {@link #RETENTION} is deleted; unread rows are never
 * touched, no matter how old.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupJob {

    /** How long a read notification is preserved before pruning. */
    private static final Duration RETENTION = Duration.ofDays(90);

    private final NotificationRepository notifRepo;
    private final ak.dev.irc.app.admin.ops.JobRunRecorder jobRunRecorder;
    private final ak.dev.irc.app.admin.ops.JobRunRepository jobRunRepository;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeOldRead() {
        if (jobPause.isPaused("notification-cleanup")) return;
        jobRunRecorder.record("notification-cleanup", null, () -> {
            LocalDateTime cutoff = LocalDateTime.now().minus(RETENTION);
            int deleted = notifRepo.deleteReadOlderThan(cutoff);
            if (deleted > 0) {
                log.info("[NOTIF-CLEANUP] Purged {} read notifications older than {}", deleted, cutoff);
            } else {
                log.debug("[NOTIF-CLEANUP] No read notifications older than {} to purge", cutoff);
            }
            // The ledger prunes itself on the same nightly cadence (90d).
            int ledger = jobRunRepository.deleteOlderThan(LocalDateTime.now().minusDays(90));
            if (ledger > 0) {
                log.info("[NOTIF-CLEANUP] Pruned {} job_runs rows older than 90d", ledger);
            }
            return new ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats(deleted, 0);
        });
    }
}
