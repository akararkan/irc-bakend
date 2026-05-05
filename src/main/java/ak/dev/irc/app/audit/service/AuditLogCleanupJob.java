package ak.dev.irc.app.audit.service;

import ak.dev.irc.app.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Daily prune of old audit rows so the table stays bounded.
 *
 * <p>Default retention is 180 days. Set {@code irc.audit.retention-days} in
 * application config to override. Compliance-driven retention requirements
 * (GDPR, SOC2) typically land in this range — increase as policy demands.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogCleanupJob {

    private final AuditLogRepository repo;

    @Value("${irc.audit.retention-days:180}")
    private int retentionDays;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        if (retentionDays <= 0) return;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = repo.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[AUDIT-CLEANUP] Pruned {} rows older than {}", deleted, cutoff);
        }
    }
}
