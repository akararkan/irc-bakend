package ak.dev.irc.app.admin.analytics;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
import ak.dev.irc.app.chat.repository.StreamGiftTallyRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.tag.repository.TrendingTagRepository;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.post.cassandra.repository.ReelsByDayRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform analytics (blueprint §3.11, analytics-kpis.md): the overview tiles
 * computable today, content-production series, the collector-backed
 * engagement view (never the private activity store), the trending wrapper,
 * and CSV export. Honest sourcing throughout — every number names where it
 * came from; nothing pretends a time-series existed before the collector.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ANALYST')")   // §6 matrix: ANALYST is read-only and this section is all reads
public class AdminAnalyticsController {

    private final UserRepository userRepository;
    private final ResearchRepository researchRepository;
    private final QuestionRepository questionRepository;
    private final ConversationRepository conversationRepository;
    private final LiveStreamRepository liveStreamRepository;
    private final StreamGiftTallyRepository giftTallyRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final ReportRepository reportRepository;
    private final ReelsByDayRepository reelsByDayRepository;
    private final TrendingTagRepository trendingTagRepository;
    private final MetricDailyService metricDailyService;
    private final ak.dev.irc.app.chat.service.PresenceService presenceService;
    private final AnalyticsEventService analyticsEventService;
    private final AnalyticsJobs analyticsJobs;
    private final UserFirstEventsRepository firstEventsRepository;
    private final CohortRetentionRepository cohortRetentionRepository;
    private final MetricAlertRepository metricAlertRepository;
    private final AnalyticsAlertConfigRepository alertConfigRepository;
    private final AdminAuditor adminAuditor;

    // ── overview ────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> tiles = new LinkedHashMap<>();
        tiles.put("totalUsers", userRepository.countByDeletedAtIsNull());
        tiles.put("signupsToday", userRepository.countByCreatedAtGreaterThanEqualAndDeletedAtIsNull(
                LocalDate.now().atStartOfDay()));
        tiles.put("publishedResearch",
                researchRepository.countByStatusAndDeletedAtIsNull(ResearchStatus.PUBLISHED));
        tiles.put("totalResearch", researchRepository.countByDeletedAtIsNull());
        tiles.put("questions", questionRepository.countByDeletedAtIsNull());
        Map<String, Long> conversations = new LinkedHashMap<>();
        for (ConversationType t : ConversationType.values()) conversations.put(t.name(), 0L);
        for (Object[] row : conversationRepository.countLiveGroupedByType()) {
            conversations.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        tiles.put("conversationsByType", conversations);
        tiles.put("liveNow", liveStreamRepository.countByStatus(LiveStreamStatus.LIVE));
        tiles.put("storageBytes", mediaAssetRepository.sumStoredBytesPlatform());
        tiles.put("giftCoinsAllTime", giftTallyRepository.totalCoins());
        tiles.put("openReports", reportRepository.countByStateIn(
                EnumSet.of(ReportState.SUBMITTED, ReportState.TRIAGED)));
        tiles.put("appealBacklog", reportRepository.countByState(ReportState.APPEALED));
        tiles.put("dauToday", metricDailyService.dauSeries(1).values().stream()
                .findFirst().orElse(0L));
        tiles.put("mau30d", metricDailyService.distinctActiveOver(30));
        tiles.put("onlineNow", presenceService.onlineNowCount());
        return ResponseEntity.ok(tiles);
    }

    // ── content production ──────────────────────────────────────────────

    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> content(
            @RequestParam(defaultValue = "30") int window) {
        int days = clampWindow(window);
        LocalDateTime from = LocalDate.now().minusDays(days - 1).atStartOfDay();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowDays", days);
        body.put("signupsPerDay", dayCounts(userRepository.signupsPerDay(from)));
        body.put("researchPerDay", dayCounts(researchRepository.createdPerDay(from)));
        body.put("questionsPerDay", dayCounts(questionRepository.createdPerDay(from)));

        // Reels: the lone date-bucketed Cassandra structure — count per day partition.
        Map<String, Long> reels = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            long n;
            try {
                n = reelsByDayRepository.countForDay(day);
            } catch (Exception e) {
                n = -1;
            }
            reels.put(day, n);
        }
        body.put("reelsPerDay", reels);
        body.put("postCreatesPerDay", metricDailyService.series("activity.POST_CREATED", days));
        body.put("note", AdminOpsMessages.NOTE_POST_CREATES_COLLECTOR_SOURCED);
        return ResponseEntity.ok(body);
    }

    // ── engagement (collector-backed) ───────────────────────────────────

    @GetMapping("/engagement")
    public ResponseEntity<Map<String, Object>> engagement(
            @RequestParam(defaultValue = "30") int window) {
        int days = clampWindow(window);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowDays", days);
        body.put("dauPerDay", metricDailyService.dauSeries(days));
        body.put("loginsPerDay", metricDailyService.series("login.success", days));
        body.put("failedLoginsPerDay", metricDailyService.series("login.failed", days));
        body.put("activityPerDay", metricDailyService.series("activity.total", days));
        body.put("reactionsPerDay", metricDailyService.series("activity.POST_REACTION", days));
        body.put("commentsPerDay", metricDailyService.series("activity.POST_COMMENT", days));
        body.put("reelWatchesPerDay", metricDailyService.series("activity.REEL_WATCH", days));
        body.put("searchesPerDay", metricDailyService.series("activity.GLOBAL_SEARCH", days));
        body.put("source", "analytics_metric_daily + analytics_dau_by_day — the parallel "
                + "fan-in collector; the private per-user activity store is never scanned "
                + "(analytics-kpis.md §12 privacy contract). Series begin at collector deployment.");
        return ResponseEntity.ok(body);
    }

    // ── trending wrapper ────────────────────────────────────────────────

    @GetMapping("/trending")
    public ResponseEntity<List<Map<String, Object>>> trending(
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(defaultValue = "20") int limit) {
        String normalized = switch (scope.trim().toUpperCase()) {
            case "ALL", "QUESTION", "RESEARCH", "POST", "REEL" -> scope.trim().toUpperCase();
            default -> "ALL";
        };
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var row : trendingTagRepository.topForScope(normalized, Pages.clamp(limit))) {
            out.add(Map.of(
                    "rank", row.getTagRank(),
                    "tag", row.getTag(),
                    "usageCount", row.getUsageCount() == null ? 0L : row.getUsageCount(),
                    "scope", normalized));
        }
        return ResponseEntity.ok(out);
    }

    // ── CSV export ──────────────────────────────────────────────────────

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam String dataset,
                                         @RequestParam(defaultValue = "30") int window) {
        int days = clampWindow(window);
        StringBuilder csv = new StringBuilder();
        switch (dataset.trim().toLowerCase()) {
            case "engagement" -> {
                csv.append("day,dau,logins,activity\n");
                Map<String, Long> dau = metricDailyService.dauSeries(days);
                Map<String, Long> logins = metricDailyService.series("login.success", days);
                Map<String, Long> activity = metricDailyService.series("activity.total", days);
                for (String day : dau.keySet()) {
                    csv.append(day).append(',').append(dau.get(day)).append(',')
                            .append(logins.getOrDefault(day, 0L)).append(',')
                            .append(activity.getOrDefault(day, 0L)).append('\n');
                }
            }
            case "signups" -> {
                csv.append("day,signups\n");
                for (Map.Entry<String, Long> e : dayCounts(userRepository.signupsPerDay(
                        LocalDate.now().minusDays(days - 1).atStartOfDay())).entrySet()) {
                    csv.append(e.getKey()).append(',').append(e.getValue()).append('\n');
                }
            }
            case "content" -> {
                csv.append("day,research,questions\n");
                LocalDateTime from = LocalDate.now().minusDays(days - 1).atStartOfDay();
                Map<String, Long> research = dayCounts(researchRepository.createdPerDay(from));
                Map<String, Long> questions = dayCounts(questionRepository.createdPerDay(from));
                java.util.TreeSet<String> daysSet = new java.util.TreeSet<>();
                daysSet.addAll(research.keySet());
                daysSet.addAll(questions.keySet());
                for (String day : daysSet) {
                    csv.append(day).append(',').append(research.getOrDefault(day, 0L))
                            .append(',').append(questions.getOrDefault(day, 0L)).append('\n');
                }
            }
            default -> throw new BadRequestException(
                    AdminOpsMessages.INVALID_DATASET_MSG, AdminOpsMessages.INVALID_DATASET);
        }
        adminAuditor.record(AuditOperation.READ, "Analytics", null,
                "ADMIN_ANALYTICS_EXPORT", dataset + " " + days + "d");
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + dataset + "-" + days + "d.csv\"")
                .body(csv.toString());
    }

    // ── metric series (rollup-merged) ───────────────────────────────────

    /** Any metric by name, rollup rows winning over live counters per day. */
    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> series(
            @RequestParam String metric,
            @RequestParam(defaultValue = "30") int window) {
        String m = metric.trim();
        if (m.isEmpty() || m.length() > 80 || !m.matches("[A-Za-z0-9._-]+")) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_METRIC_MSG, AdminOpsMessages.INVALID_METRIC);
        }
        int days = clampWindow(window);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metric", m);
        body.put("windowDays", days);
        body.put("series", metricDailyService.mergedSeries(m, days));
        body.put("source", "analytics_metric_rollup (durable, rollup-job) merged over "
                + "analytics_metric_daily (live counters) — rollup wins per day.");
        return ResponseEntity.ok(body);
    }

    // ── activation funnel ───────────────────────────────────────────────

    /** signed-up → first-seen → profile-completed → first-follow → first-content
     *  for one signup month ({@code cohort=YYYY-MM}, default: current month). */
    @GetMapping("/funnel")
    public ResponseEntity<Map<String, Object>> funnel(
            @RequestParam(required = false) String cohort) {
        LocalDate monthStart;
        try {
            monthStart = cohort == null || cohort.isBlank()
                    ? LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                    : LocalDate.parse(cohort.trim() + "-01");
        } catch (Exception e) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_COHORT_MSG, AdminOpsMessages.INVALID_COHORT);
        }
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = monthStart.plusMonths(1).atStartOfDay();
        List<java.util.UUID> ids = userRepository.findIdsCreatedBetween(
                from, to, org.springframework.data.domain.PageRequest.of(0, 50_000));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cohort", monthStart.getYear() + "-" + String.format("%02d", monthStart.getMonthValue()));
        body.put("signedUp", ids.size());
        if (ids.isEmpty()) {
            body.put("firstSeen", 0);
            body.put("profileCompleted", 0);
            body.put("firstFollow", 0);
            body.put("firstContent", 0);
        } else {
            body.put("firstSeen", firstEventsRepository.countFirstSeen(ids));
            body.put("profileCompleted", firstEventsRepository.countProfileCompleted(ids));
            body.put("firstFollow", firstEventsRepository.countFirstFollow(ids));
            body.put("firstContent", firstEventsRepository.countFirstContent(ids));
        }
        body.put("note", AdminOpsMessages.NOTE_FUNNEL_MILESTONES_SET_ONCE);
        return ResponseEntity.ok(body);
    }

    // ── weekly retention grid ───────────────────────────────────────────

    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> retention(
            @RequestParam(defaultValue = "12") int weeks) {
        int n = Math.max(1, Math.min(weeks, 26));
        LocalDate thisMonday = LocalDate.now(ZoneOffset.UTC)
                .with(java.time.DayOfWeek.MONDAY);
        List<String> wanted = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) wanted.add(thisMonday.minusWeeks(i).toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weeks", n);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (CohortRetention cell : cohortRetentionRepository.grid(wanted)) {
            rows.add(Map.of(
                    "cohortWeek", cell.getCohortWeek(),
                    "weekOffset", cell.getWeekOffset(),
                    "cohortSize", cell.getCohortSize(),
                    "activeCount", cell.getActiveCount(),
                    "retentionPct", cell.getCohortSize() == 0 ? 0.0
                            : Math.round(cell.getActiveCount() * 10000.0 / cell.getCohortSize()) / 100.0));
        }
        body.put("grid", rows);
        body.put("computedBy", "WeeklyCohortJob (Mondays 03:10 UTC) over analytics_dau_by_day; "
                + "POST /rollup/{date}/run does not refresh this grid.");
        return ResponseEntity.ok(body);
    }

    // ── rollup control ──────────────────────────────────────────────────

    @org.springframework.web.bind.annotation.PostMapping("/rollup/{date}/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runRollup(
            @org.springframework.web.bind.annotation.PathVariable String date) {
        LocalDate day = parseDay(date);
        int written = analyticsJobs.rollupFor(day);
        adminAuditor.record(AuditOperation.UPDATE, "Analytics", null,
                "ADMIN_ANALYTICS_ROLLUP_RUN", day.toString());
        return ResponseEntity.ok(Map.of("day", day.toString(), "metricRowsWritten", written));
    }

    /** Re-derive rollups for an inclusive date range (≤ 90 days per call). */
    @org.springframework.web.bind.annotation.PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> backfill(@RequestParam String from,
                                                        @RequestParam String to) {
        LocalDate start = parseDay(from);
        LocalDate end = parseDay(to);
        if (end.isBefore(start)) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_RANGE_MSG, AdminOpsMessages.INVALID_RANGE);
        }
        long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (span > 90) {
            throw new BadRequestException(
                    AdminOpsMessages.RANGE_TOO_LARGE_MSG, AdminOpsMessages.RANGE_TOO_LARGE);
        }
        int written = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            written += analyticsJobs.rollupFor(d);
        }
        adminAuditor.record(AuditOperation.UPDATE, "Analytics", null,
                "ADMIN_ANALYTICS_BACKFILL", start + ".." + end);
        return ResponseEntity.ok(Map.of(
                "from", start.toString(), "to", end.toString(),
                "daysProcessed", span, "metricRowsWritten", written));
    }

    // ── raw event sampling (step-up, ADMIN only, audited) ───────────────

    /** Peek at raw {@code analytics_events} rows — actor ids are visible here,
     *  so this is the one analytics read that is step-up-gated and audited
     *  as raw-data access (analytics-kpis.md §12). */
    @GetMapping("/events/sample")
    @PreAuthorize("hasRole('ADMIN')")
    @ak.dev.irc.app.admin.support.RequiresStepUp
    public ResponseEntity<Map<String, Object>> eventsSample(
            @RequestParam(required = false) String day,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit) {
        LocalDate d = day == null || day.isBlank()
                ? LocalDate.now(ZoneOffset.UTC) : parseDay(day);
        adminAuditor.record(AuditOperation.READ, "Analytics", null,
                "ADMIN_ANALYTICS_RAW_ACCESS",
                d + (type == null ? "" : " type=" + type));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("day", d.toString());
        body.put("rows", analyticsEventService.sample(d, type, Pages.clamp(limit)));
        body.put("counts", analyticsEventService.countsByTypeForDay(d));
        return ResponseEntity.ok(body);
    }

    // ── anomaly configuration + firings ─────────────────────────────────

    @GetMapping("/alerts-config")
    public ResponseEntity<List<AnalyticsAlertConfig>> alertsConfig() {
        return ResponseEntity.ok(alertConfigRepository.findAll());
    }

    public record AlertConfigRequest(Double zWarn, Double zAlert, Long minVolume, Boolean enabled) {}

    @org.springframework.web.bind.annotation.PutMapping("/alerts/{metric}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AnalyticsAlertConfig> upsertAlertConfig(
            @org.springframework.web.bind.annotation.PathVariable String metric,
            @org.springframework.web.bind.annotation.RequestBody AlertConfigRequest req) {
        String m = metric.trim();
        if (m.isEmpty() || m.length() > 80 || !m.matches("[A-Za-z0-9._-]+")) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_METRIC_MSG, AdminOpsMessages.INVALID_METRIC);
        }
        if ((req.zWarn() != null && req.zWarn() <= 0)
                || (req.zAlert() != null && req.zAlert() <= 0)
                || (req.minVolume() != null && req.minVolume() < 0)) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_THRESHOLDS_MSG, AdminOpsMessages.INVALID_THRESHOLDS);
        }
        AnalyticsAlertConfig cfg = alertConfigRepository.findById(m)
                .orElseGet(() -> AnalyticsAlertConfig.builder().metric(m).build());
        if (req.zWarn() != null) cfg.setZWarn(req.zWarn());
        if (req.zAlert() != null) cfg.setZAlert(req.zAlert());
        if (req.minVolume() != null) cfg.setMinVolume(req.minVolume());
        if (req.enabled() != null) cfg.setEnabled(req.enabled());
        if (cfg.getZAlert() < cfg.getZWarn()) {
            throw new BadRequestException(AdminOpsMessages.INVALID_THRESHOLDS_Z_ORDER_MSG,
                    AdminOpsMessages.INVALID_THRESHOLDS);
        }
        cfg = alertConfigRepository.save(cfg);
        adminAuditor.record(AuditOperation.UPDATE, "Analytics", null,
                "ADMIN_ANALYTICS_ALERT_CONFIG", m);
        return ResponseEntity.ok(cfg);
    }

    @GetMapping("/anomalies")
    public ResponseEntity<org.springframework.data.domain.Page<MetricAlert>> anomalies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(metricAlertRepository.browse(
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), Pages.clamp(size))));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static LocalDate parseDay(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_DATE_MSG, AdminOpsMessages.INVALID_DATE);
        }
    }

    private static Map<String, Long> dayCounts(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return out;
    }

    private static int clampWindow(int window) {
        return Math.max(1, Math.min(window, 365));
    }
}
