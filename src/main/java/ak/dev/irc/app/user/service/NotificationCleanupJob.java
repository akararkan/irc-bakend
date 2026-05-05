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

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeOldRead() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RETENTION);
        int deleted = notifRepo.deleteReadOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[NOTIF-CLEANUP] Purged {} read notifications older than {}", deleted, cutoff);
        } else {
            log.debug("[NOTIF-CLEANUP] No read notifications older than {} to purge", cutoff);
        }
    }
}
