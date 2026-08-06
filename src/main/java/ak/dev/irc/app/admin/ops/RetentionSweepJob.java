package ak.dev.irc.app.admin.ops;

import ak.dev.irc.app.security.login.repository.LoginEventRepository;
import ak.dev.irc.app.security.otp.repository.OtpChallengeRepository;
import ak.dev.irc.app.settings.audit.repository.SettingsAuditRepository;
import ak.dev.irc.app.settings.data.entity.ExportJob;
import ak.dev.irc.app.settings.data.repository.ExportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Daily retention sweep (logs-audit.md §7) — the enforcement the retention
 * matrix recommended but nothing ran:
 *
 * <ul>
 *   <li><b>Export ZIPs</b> — the highest-priority fix: expired PII archives
 *       sat on disk forever ({@code expires_at} was set, nothing enforced
 *       it). Deletes file + row past expiry.</li>
 *   <li><b>otp_challenges</b> — 180 d on the indexed expiry column.</li>
 *   <li><b>settings_audit</b> — 2 y.</li>
 *   <li><b>login_events</b> — 1 y.</li>
 *   <li><b>dead_letters</b> — 90 d.</li>
 * </ul>
 *
 * The Cassandra audit tables need no job — their 180 d TTL is applied by
 * {@code AuditSchemaInitializer}. Strikes/reports/consent are never
 * auto-deleted (evidence stores, by design).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionSweepJob {

    private final ExportJobRepository exportJobRepository;
    private final OtpChallengeRepository otpChallengeRepository;
    private final SettingsAuditRepository settingsAuditRepository;
    private final LoginEventRepository loginEventRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final JobRunRecorder jobRunRecorder;
    private final JobPauseRegistry jobPause;

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void sweep() {
        if (jobPause.isPaused("retention-sweep")) return;
        jobRunRecorder.record("retention-sweep", null, () -> {
            int processed = 0, failed = 0;
            LocalDateTime now = LocalDateTime.now();

            // Export ZIPs past expiry — delete the FILE first, then the row.
            for (ExportJob job : exportJobRepository.findAll()) {
                if (job.getExpiresAt() == null || job.getExpiresAt().isAfter(now)) continue;
                try {
                    if (job.getFilePath() != null) {
                        Files.deleteIfExists(Path.of(job.getFilePath()));
                    }
                    exportJobRepository.delete(job);
                    processed++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[RETENTION] export job {} cleanup failed: {}", job.getId(), e.getMessage());
                }
            }

            processed += sweepStep("otp_challenges 180d",
                    () -> otpChallengeRepository.deleteExpiredBefore(now.minusDays(180)));
            processed += sweepStep("settings_audit 2y",
                    () -> settingsAuditRepository.deleteOlderThan(now.minusYears(2)));
            processed += sweepStep("login_events 1y",
                    () -> loginEventRepository.deleteOlderThan(now.minusYears(1)));
            processed += sweepStep("dead_letters 90d",
                    () -> deadLetterRepository.deleteOlderThan(now.minusDays(90)));

            return new JobRunRecorder.JobStats(processed, failed);
        });
    }

    private int sweepStep(String what, java.util.function.IntSupplier step) {
        try {
            int n = step.getAsInt();
            if (n > 0) log.info("[RETENTION] {} — deleted {}", what, n);
            return n;
        } catch (Exception e) {
            log.warn("[RETENTION] {} failed: {}", what, e.getMessage());
            return 0;
        }
    }
}
