package ak.dev.irc.app.admin.analytics;

import ak.dev.irc.app.admin.ops.JobRunRecorder;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The three analytics collectors (analytics-kpis.md §6.4/§11), colocated:
 *
 * <ul>
 *   <li><b>DailyRollupJob</b> (02:40 UTC) — folds D-1 into
 *       {@code analytics_metric_rollup}: raw {@code analytics_events} type
 *       counts, the live counter values (making them durable), and the
 *       PG-derived daily aggregates (signups, research, questions, reports).
 *       Idempotent — plain-column overwrite, safe to re-run any date.</li>
 *   <li><b>WeeklyCohortJob</b> (Mondays 03:10 UTC) — recomputes the 12-week
 *       signup-cohort retention grid from {@code analytics_dau_by_day}.</li>
 *   <li><b>AnomalyScanJob</b> (02:55 UTC, after the rollup) — z-score over
 *       the trailing 28 completed days per watched metric; firings land in
 *       {@code metric_alerts} + an ADMIN_ANOMALY notification to admins.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsJobs {

    private static final List<String> DEFAULT_WATCHLIST = List.of(
            "login.success", "activity.total", "signups", "reports.submitted",
            "research.created", "questions.created");

    private final AnalyticsEventService analyticsEventService;
    private final MetricDailyService metricDailyService;
    private final CohortRetentionRepository cohortRepository;
    private final MetricAlertRepository metricAlertRepository;
    private final AnalyticsAlertConfigRepository alertConfigRepository;
    private final UserRepository userRepository;
    private final ak.dev.irc.app.research.repository.ResearchRepository researchRepository;
    private final ak.dev.irc.app.qna.repository.QuestionRepository questionRepository;
    private final ak.dev.irc.app.settings.safety.repository.ReportRepository reportRepository;
    private final CassandraNotificationService notifications;
    private final JobRunRecorder jobRunRecorder;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;
    private final org.springframework.data.cassandra.core.cql.CqlOperations cqlOperations;

    // ── daily rollup ────────────────────────────────────────────────────

    @Scheduled(cron = "0 40 2 * * *", zone = "UTC")
    public void dailyRollup() {
        if (jobPause.isPaused("analytics-daily-rollup")) return;
        jobRunRecorder.record("analytics-daily-rollup", null, () -> {
            int written = rollupFor(LocalDate.now(ZoneOffset.UTC).minusDays(1));
            return new JobRunRecorder.JobStats(written, 0);
        });
    }

    /** Idempotent per-date rollup — also the body of the re-run/backfill endpoints. */
    public int rollupFor(LocalDate day) {
        int written = 0;
        // 1) Raw event-tap counts → events.{routingKey}
        for (Map.Entry<String, Long> e : analyticsEventService.countsByTypeForDay(day).entrySet()) {
            metricDailyService.writeRollup("events." + e.getKey(), day, e.getValue());
            written++;
        }
        // 2) Durable copies of the live tee counters.
        for (String metric : List.of("login.success", "login.failed", "activity.total")) {
            metricDailyService.writeRollup(metric, day, metricDailyService.liveValueFor(metric, day));
            written++;
        }
        // 3) PG-derived dailies (exact, cheap, index-backed).
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.atTime(LocalTime.MAX);
        metricDailyService.writeRollup("signups", day, countRows(userRepository.signupsPerDay(from), day));
        metricDailyService.writeRollup("research.created", day,
                countRows(researchRepository.createdPerDay(from), day));
        metricDailyService.writeRollup("questions.created", day,
                countRows(questionRepository.createdPerDay(from), day));
        long reports = reportRepository.countByReasonBetween(from, to).stream()
                .mapToLong(r -> ((Number) r[1]).longValue()).sum();
        metricDailyService.writeRollup("reports.submitted", day, reports);
        written += 4;
        log.info("[ANALYTICS] rollup for {} wrote {} metric rows", day, written);
        return written;
    }

    private static long countRows(List<Object[]> perDay, LocalDate day) {
        for (Object[] row : perDay) {
            if (String.valueOf(row[0]).equals(day.toString())) {
                return ((Number) row[1]).longValue();
            }
        }
        return 0;
    }

    // ── weekly cohort retention ─────────────────────────────────────────

    @Scheduled(cron = "0 10 3 * * MON", zone = "UTC")
    public void weeklyCohorts() {
        if (jobPause.isPaused("analytics-weekly-cohorts")) return;
        jobRunRecorder.record("analytics-weekly-cohorts", null, () -> {
            int cells = computeCohorts(12);
            return new JobRunRecorder.JobStats(cells, 0);
        });
    }

    public int computeCohorts(int weeks) {
        int cells = 0;
        LocalDate thisMonday = LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY);
        for (int c = 1; c <= weeks; c++) {
            LocalDate cohortMonday = thisMonday.minusWeeks(c);
            List<UUID> cohort = userRepository.findIdsCreatedBetween(
                    cohortMonday.atStartOfDay(), cohortMonday.plusWeeks(1).atStartOfDay(),
                    PageRequest.of(0, 20_000));
            if (cohort.isEmpty()) continue;
            Set<UUID> cohortSet = new HashSet<>(cohort);
            int maxOffset = (int) java.time.temporal.ChronoUnit.WEEKS.between(cohortMonday, thisMonday);
            for (int offset = 0; offset < maxOffset; offset++) {
                LocalDate weekStart = cohortMonday.plusWeeks(offset);
                long active = activeCohortMembersInWeek(cohortSet, weekStart);
                upsertCell(cohortMonday.toString(), offset, cohort.size(), active);
                cells++;
            }
        }
        log.info("[ANALYTICS] cohort grid recomputed — {} cells", cells);
        return cells;
    }

    private long activeCohortMembersInWeek(Set<UUID> cohort, LocalDate weekStart) {
        Set<UUID> activeThisWeek = new HashSet<>();
        for (int d = 0; d < 7; d++) {
            String day = weekStart.plusDays(d).toString();
            try {
                var rows = cqlOperations.queryForList(
                        "SELECT user_id FROM analytics_dau_by_day WHERE day = ?", day);
                for (Map<String, Object> row : rows) {
                    Object id = row.get("user_id");
                    if (id instanceof UUID uuid && cohort.contains(uuid)) {
                        activeThisWeek.add(uuid);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return activeThisWeek.size();
    }

    private void upsertCell(String cohortWeek, int offset, long size, long active) {
        try {
            CohortRetention cell = cohortRepository.cell(cohortWeek, offset)
                    .orElseGet(() -> CohortRetention.builder()
                            .cohortWeek(cohortWeek).weekOffset(offset).build());
            cell.setCohortSize(size);
            cell.setActiveCount(active);
            cell.setComputedAt(LocalDateTime.now());
            cohortRepository.save(cell);
        } catch (Exception e) {
            log.debug("[ANALYTICS] cohort cell {}+{} failed: {}", cohortWeek, offset, e.getMessage());
        }
    }

    // ── anomaly scan ────────────────────────────────────────────────────

    @Scheduled(cron = "0 55 2 * * *", zone = "UTC")
    public void anomalyScan() {
        if (jobPause.isPaused("analytics-anomaly-scan")) return;
        jobRunRecorder.record("analytics-anomaly-scan", null, () -> {
            int flagged = scanForAnomalies();
            return new JobRunRecorder.JobStats(flagged, 0);
        });
    }

    public int scanForAnomalies() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        List<AnalyticsAlertConfig> configs = alertConfigRepository.enabledConfigs();
        List<String> metrics = configs.isEmpty()
                ? DEFAULT_WATCHLIST
                : configs.stream().map(AnalyticsAlertConfig::getMetric).toList();
        int flagged = 0;
        for (String metric : metrics) {
            try {
                AnalyticsAlertConfig cfg = alertConfigRepository.findById(metric)
                        .orElseGet(() -> AnalyticsAlertConfig.builder().metric(metric).build());
                if (!cfg.isEnabled()) continue;
                if (metricAlertRepository.countForDayAndMetric(yesterday.toString(), metric) > 0) {
                    continue;   // already flagged
                }
                Map<String, Long> series = metricDailyService.mergedSeries(metric, 29);
                Long value = series.remove(yesterday.toString());
                if (value == null) continue;
                List<Long> baseline = new ArrayList<>(series.values());
                double mean = baseline.stream().mapToLong(Long::longValue).average().orElse(0);
                if (mean < cfg.getMinVolume()) continue;   // noise floor
                double sd = Math.sqrt(baseline.stream()
                        .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
                boolean pipelineDead = value == 0 && mean >= cfg.getMinVolume();
                double z = sd == 0 ? (value == mean ? 0 : Double.MAX_VALUE)
                        : Math.abs(value - mean) / sd;
                String severity = pipelineDead || z >= cfg.getZAlert() ? "ALERT"
                        : z >= cfg.getZWarn() ? "WARN" : null;
                if (severity == null) continue;

                metricAlertRepository.save(MetricAlert.builder()
                        .day(yesterday.toString()).metric(metric)
                        .z(Math.min(z, 999)).value(value).mean(mean).sd(sd)
                        .severity(severity).build());
                notifyAdmins(metric, yesterday, value, mean, severity, pipelineDead);
                flagged++;
            } catch (Exception e) {
                log.warn("[ANALYTICS] anomaly scan for {} failed: {}", metric, e.getMessage());
            }
        }
        return flagged;
    }

    private void notifyAdmins(String metric, LocalDate day, long value, double mean,
                              String severity, boolean pipelineDead) {
        String body = pipelineDead
                ? "Metric '" + metric + "' flatlined at 0 on " + day + " (28d mean "
                        + Math.round(mean) + ") — pipeline-dead detector."
                : severity + ": metric '" + metric + "' was " + value + " on " + day
                        + " vs a 28d mean of " + Math.round(mean) + ".";
        try {
            var admins = userRepository.findActiveByRoles(List.of(Role.ADMIN), PageRequest.of(0, 50));
            for (var admin : admins.getContent()) {
                notifications.deliverAsync(new CassandraNotificationService.DeliverRequest(
                        admin.getId(), NotificationKind.ADMIN_ANOMALY,
                        "Metric anomaly: " + metric, body,
                        null, "Metric", null, "ANOMALY:" + day + ":" + metric));
            }
        } catch (Exception e) {
            log.debug("[ANALYTICS] anomaly notification failed: {}", e.getMessage());
        }
    }
}
