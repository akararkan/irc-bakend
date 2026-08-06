package ak.dev.irc.app.admin.logs;

import ak.dev.irc.app.admin.ops.DeadLetterRepository;
import ak.dev.irc.app.admin.ops.JobRunRepository;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.security.login.repository.LoginEventRepository;
import ak.dev.irc.app.security.otp.repository.OtpChallengeRepository;
import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import ak.dev.irc.app.settings.data.repository.AccountDeletionRequestRepository;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The log alert-rule evaluator (logs-audit.md §6.2): a 5-minute sweep over
 * the PG stores, firing rows into {@code log_alert_firings} + a system
 * notification to every ADMIN (the security channel bypasses DND). Each
 * condition dedups on (kind + subject) inside its own window, so a
 * persisting condition fires once per window, not once per tick.
 * Default rules are seeded on first startup and editable via the CRUD.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogAlertSweepJob {

    private final LogAlertRuleRepository ruleRepository;
    private final LogAlertFiringRepository firingRepository;
    private final LoginEventRepository loginEventRepository;
    private final ReportRepository reportRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final OtpChallengeRepository otpChallengeRepository;
    private final JobRunRepository jobRunRepository;
    private final AccountDeletionRequestRepository deletionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaults() {
        try {
            if (ruleRepository.count() > 0) return;
            ruleRepository.saveAll(List.of(
                    rule(LogAlertRule.RuleKind.FAILED_LOGIN_PER_ACCOUNT,
                            "Failed-login spike (per account)", 10, 15, LogAlertRule.Severity.HIGH),
                    rule(LogAlertRule.RuleKind.FAILED_LOGIN_PER_IP,
                            "Failed-login spike (per IP)", 50, 60, LogAlertRule.Severity.HIGH),
                    rule(LogAlertRule.RuleKind.REPORT_PILE_ON,
                            "Report pile-on (one target)", 10, 24 * 60, LogAlertRule.Severity.MEDIUM),
                    rule(LogAlertRule.RuleKind.DLQ_ARRIVALS,
                            "Dead-letter arrivals", 1, 60, LogAlertRule.Severity.MEDIUM),
                    rule(LogAlertRule.RuleKind.OTP_ABUSE,
                            "OTP challenge volume", 20, 60, LogAlertRule.Severity.MEDIUM),
                    rule(LogAlertRule.RuleKind.PURGE_JOB_SILENCE,
                            "Account-purge job silence", 1, 26 * 60, LogAlertRule.Severity.MEDIUM)));
            log.info("[LOG-ALERTS] seeded {} default rules", 6);
        } catch (Exception e) {
            log.warn("[LOG-ALERTS] default-rule seed failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${irc.logs.alert-sweep-ms:300000}")
    public void sweep() {
        if (jobPause.isPaused("log-alert-sweep")) return;
        List<LogAlertRule> rules;
        try {
            rules = ruleRepository.enabledRules();
        } catch (Exception e) {
            return;   // table not ready yet — next tick
        }
        for (LogAlertRule rule : rules) {
            try {
                evaluate(rule);
            } catch (Exception e) {
                log.warn("[LOG-ALERTS] rule {} evaluation failed: {}", rule.getKind(), e.getMessage());
            }
        }
    }

    private void evaluate(LogAlertRule rule) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(rule.getWindowMinutes());
        switch (rule.getKind()) {
            case FAILED_LOGIN_PER_ACCOUNT -> {
                for (Object[] row : loginEventRepository.failedByUserSince(since, rule.getThreshold())) {
                    fire(rule, "user=" + row[0],
                            AdminOpsMessages.NOTIF_LOG_ALERT_FAILED_LOGIN_ACCOUNT_BODY
                                    .formatted(row[1], rule.getWindowMinutes(), row[0]));
                }
            }
            case FAILED_LOGIN_PER_IP -> {
                for (Object[] row : loginEventRepository.failedByIpSince(since, rule.getThreshold())) {
                    fire(rule, "ip=" + row[0],
                            AdminOpsMessages.NOTIF_LOG_ALERT_FAILED_LOGIN_IP_BODY
                                    .formatted(row[1], rule.getWindowMinutes(), row[0]));
                }
            }
            case REPORT_PILE_ON -> {
                for (Object[] row : reportRepository.pileOnGroupsSince(since, rule.getThreshold())) {
                    fire(rule, "group=" + row[0],
                            AdminOpsMessages.NOTIF_LOG_ALERT_REPORT_PILE_ON_BODY
                                    .formatted(row[1], row[0], rule.getWindowMinutes()));
                }
            }
            case DLQ_ARRIVALS -> {
                long arrivals = deadLetterRepository.countArrivedSince(since);
                if (arrivals >= rule.getThreshold()) {
                    fire(rule, "dlq",
                            AdminOpsMessages.NOTIF_LOG_ALERT_DLQ_BODY
                                    .formatted(arrivals, rule.getWindowMinutes()));
                }
            }
            case OTP_ABUSE -> {
                long issued = otpChallengeRepository.countIssuedSince(since);
                if (issued >= rule.getThreshold()) {
                    fire(rule, "otp",
                            AdminOpsMessages.NOTIF_LOG_ALERT_OTP_BODY
                                    .formatted(issued, rule.getWindowMinutes()));
                }
            }
            case PURGE_JOB_SILENCE -> {
                boolean pendingOverdue = !deletionRepository.findByStatusAndPurgeAfterBefore(
                        DeletionStatus.PENDING_DELETION, LocalDateTime.now()).isEmpty();
                boolean ranRecently = jobRunRepository
                        .findFirstByJobNameOrderByStartedAtDesc("account-purge")
                        .map(run -> run.getStartedAt().isAfter(since))
                        .orElse(false);
                if (pendingOverdue && !ranRecently) {
                    fire(rule, "purge-silence",
                            AdminOpsMessages.NOTIF_LOG_ALERT_PURGE_SILENCE_BODY
                                    .formatted(rule.getWindowMinutes()));
                }
            }
        }
    }

    private void fire(LogAlertRule rule, String subject, String detail) {
        String dedupKey = rule.getKind() + ":" + subject;
        LocalDateTime dedupSince = LocalDateTime.now().minusMinutes(rule.getWindowMinutes());
        if (firingRepository.countRecentByDedup(dedupKey, dedupSince) > 0) {
            return;   // already fired inside this window
        }
        firingRepository.save(LogAlertFiring.builder()
                .ruleId(rule.getId())
                .ruleKind(rule.getKind().name())
                .severity(rule.getSeverity().name())
                .detail(detail.length() > 500 ? detail.substring(0, 500) : detail)
                .dedupKey(dedupKey)
                .build());
        log.warn("[LOG-ALERTS] {} fired: {}", rule.getKind(), detail);
        notifyAdmins(rule, detail);
    }

    private void notifyAdmins(LogAlertRule rule, String detail) {
        try {
            var admins = userRepository.findActiveByRoles(List.of(Role.ADMIN), PageRequest.of(0, 50));
            for (var admin : admins.getContent()) {
                try {
                    notificationService.sendSystemNotification(admin.getId(),
                            AdminOpsMessages.NOTIF_LOG_ALERT_TITLE.formatted(rule.getName()), detail);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[LOG-ALERTS] admin notification failed: {}", e.getMessage());
        }
    }

    private static LogAlertRule rule(LogAlertRule.RuleKind kind, String name, long threshold,
                                     int windowMinutes, LogAlertRule.Severity severity) {
        return LogAlertRule.builder()
                .kind(kind).name(name).threshold(threshold)
                .windowMinutes(windowMinutes).severity(severity).build();
    }
}
