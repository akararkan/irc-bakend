package ak.dev.irc.app.admin.logs;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.cassandra.repository.AuditLogByUserRepository;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.security.login.repository.LoginEventRepository;
import ak.dev.irc.app.settings.audit.repository.SettingsAuditRepository;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The unified Log Explorer + its satellites (logs-audit.md §4/§5): the §4.1
 * grammar over per-store adapters merged by time, the global login-events
 * reader, the step-up-gated CSV export (the most sensitive read in the
 * system — it writes its own audit row including the query), per-admin saved
 * views with the documented seed set, alert-rule CRUD + the firings feed,
 * and the retention status board.
 *
 * <p>Honest grammar: the Cassandra audit store requires a {@code user:}
 * anchor (per-user partitions — a global scan is impossible and the API says
 * so instead of faking it); PG-backed stores accept anchor-free time ranges.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT','ANALYST')")
public class AdminLogsController {

    private static final int EXPORT_ROW_CAP = 10_000;

    private final AuditLogByUserRepository auditLogByUserRepository;
    private final LoginEventRepository loginEventRepository;
    private final SettingsAuditRepository settingsAuditRepository;
    private final ConsentService consentService;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final SavedLogViewRepository savedViewRepository;
    private final LogAlertRuleRepository alertRuleRepository;
    private final LogAlertFiringRepository alertFiringRepository;
    private final ak.dev.irc.app.security.otp.repository.OtpChallengeRepository otpChallengeRepository;
    private final AdminAuditor adminAuditor;

    public record SavedViewBody(@NotBlank @Size(max = 80) String name,
                                @NotBlank String query) {
    }

    public record AlertRuleBody(String kind, @Size(max = 120) String name,
                                Long threshold, Integer windowMinutes,
                                String severity, Boolean enabled) {
    }

    public record ExportBody(@NotBlank String query) {
    }

    // ── the explorer ────────────────────────────────────────────────────

    /**
     * Grammar query ({@code q}) or discrete params — both accepted. Stores:
     * {@code audit} (user-anchored), {@code login}, {@code settings},
     * {@code consent} (user-anchored), {@code reports}. Results merge by
     * time, newest first, each row wearing its store badge.
     */
    @GetMapping("/explore")
    public ResponseEntity<Map<String, Object>> explore(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String store,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "50") int pageSize) {
        final ExploreQuery query = resolveUsernameAnchor(
                ExploreQuery.of(q, store, userId, ip, outcome, since, until, text));
        int size = Pages.clamp(pageSize);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (query.wantsStore("audit")) {
            if (query.userId() == null) {
                notes.add("audit store skipped — it is partitioned per user; add user:<id|@username> "
                        + "to include it (a global Cassandra scan is impossible by design).");
            } else {
                auditLogByUserRepository.firstPage(query.userId(), size).stream()
                        .filter(r -> query.matchesTime(toLdt(r.getCreatedAt())))
                        .filter(r -> query.matchesText(r.getSummary()))
                        .forEach(r -> rows.add(row("audit", toLdt(r.getCreatedAt()),
                                r.getSummary(),
                                Map.of("operation", String.valueOf(r.getOperation()),
                                        "outcome", String.valueOf(r.getOutcome()),
                                        "path", String.valueOf(r.getPath()),
                                        "status", String.valueOf(r.getStatusCode())))));
                notes.add("audit rows are the newest page of the user partition with filters applied "
                        + "in-memory — fetch more via /api/v1/admin/audit?userId=.");
            }
        }
        if (query.wantsStore("login")) {
            loginEventRepository.explore(query.userId(), query.ip(), query.outcome(),
                            query.since(), query.until(), PageRequest.of(0, size))
                    .filter(e -> query.matchesText(e.getUserAgent()))
                    .forEach(e -> rows.add(row("login", e.getTs(),
                            e.getMethod() + " login " + e.getOutcome()
                                    + (e.getIp() != null ? " from " + e.getIp() : ""),
                            Map.of("userId", String.valueOf(e.getUserId()),
                                    "ip", String.valueOf(e.getIp()),
                                    "outcome", String.valueOf(e.getOutcome())))));
        }
        if (query.wantsStore("settings")) {
            settingsAuditRepository.explore(query.userId(), query.since(), query.until(),
                            PageRequest.of(0, size))
                    .filter(a -> query.matchesText(a.getSettingKey()))
                    .forEach(a -> rows.add(row("settings", a.getCreatedAt(),
                            a.getSettingKey() + " changed",
                            Map.of("userId", String.valueOf(a.getUserId()),
                                    "key", a.getSettingKey(),
                                    "ip", String.valueOf(a.getIp())))));
        }
        if (query.wantsStore("consent")) {
            if (query.userId() == null) {
                notes.add("consent store skipped — user-anchored; add user: to include it.");
            } else {
                consentService.history(query.userId(), PageRequest.of(0, size))
                        .filter(c -> query.matchesTime(c.getOccurredAt()))
                        .forEach(c -> rows.add(row("consent", c.getOccurredAt(),
                                c.getScope() + (c.isGranted() ? " granted" : " revoked"),
                                Map.of("scope", c.getScope(),
                                        "granted", String.valueOf(c.isGranted())))));
            }
        }
        if (query.wantsStore("reports")) {
            reportRepository.exploreByTime(query.since(), query.until(), PageRequest.of(0, size))
                    .filter(r -> query.userId() == null
                            || query.userId().equals(r.getReporterId())
                            || query.userId().equals(r.getTargetId()))
                    .forEach(r -> rows.add(row("reports", r.getCreatedAt(),
                            r.getReason() + " report on " + r.getTargetType()
                                    + " " + r.targetRefOrId() + " (" + r.getState() + ")",
                            Map.of("state", String.valueOf(r.getState()),
                                    "groupKey", r.getGroupKey()))));
        }

        rows.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
        List<Map<String, Object>> capped = rows.size() > size ? rows.subList(0, size) : rows;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.describe());
        body.put("rows", capped);
        body.put("notes", notes);
        return ResponseEntity.ok(body);
    }

    /** Global login-events reader (A4) — userId | ip | outcome filters. */
    @GetMapping("/login-events")
    public ResponseEntity<?> loginEvents(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(loginEventRepository.explore(userId,
                blankToNull(ip), blankToNull(outcome), from, to,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    /**
     * CSV export of an explorer query (A6) — the most sensitive read in the
     * system: step-up-gated, ADMIN-only, bounded, and it writes its own audit
     * row carrying the query string.
     */
    @PostMapping(value = "/export", produces = "text/csv")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> export(@RequestBody ExportBody body) {
        final ExploreQuery query = resolveUsernameAnchor(
                ExploreQuery.of(body.query(), null, null, null, null, null, null, null));
        adminAuditor.record(AuditOperation.READ, "LogExport", null,
                "ADMIN_LOG_EXPORT", "query=" + body.query());

        StringBuilder csv = new StringBuilder("store,at,summary\n");
        int written = 0;
        if (query.userId() != null && query.wantsStore("audit")) {
            for (var r : auditLogByUserRepository.firstPage(query.userId(), EXPORT_ROW_CAP / 2)) {
                if (written++ >= EXPORT_ROW_CAP) break;
                csv.append("audit,").append(toLdt(r.getCreatedAt())).append(',')
                        .append(escapeCsv(r.getSummary())).append('\n');
            }
        }
        if (query.wantsStore("login")) {
            for (var e : loginEventRepository.explore(query.userId(), query.ip(), query.outcome(),
                    query.since(), query.until(), PageRequest.of(0, EXPORT_ROW_CAP / 2))) {
                if (written++ >= EXPORT_ROW_CAP) break;
                csv.append("login,").append(e.getTs()).append(',')
                        .append(escapeCsv(e.getMethod() + " " + e.getOutcome()
                                + " ip=" + e.getIp())).append('\n');
            }
        }
        if (query.wantsStore("settings")) {
            for (var a : settingsAuditRepository.explore(query.userId(), query.since(), query.until(),
                    PageRequest.of(0, Math.max(1, EXPORT_ROW_CAP - written)))) {
                if (written++ >= EXPORT_ROW_CAP) break;
                csv.append("settings,").append(a.getCreatedAt()).append(',')
                        .append(escapeCsv(a.getSettingKey() + " changed by " + a.getUserId()))
                        .append('\n');
            }
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"log-export.csv\"")
                .header("X-Row-Cap", String.valueOf(EXPORT_ROW_CAP))
                .body(csv.toString());
    }

    // ── saved views (A7) ────────────────────────────────────────────────

    @GetMapping("/views")
    public ResponseEntity<List<AdminSavedLogView>> views(@AuthenticationPrincipal User admin) {
        List<AdminSavedLogView> views = savedViewRepository.forAdmin(admin.getId());
        if (views.isEmpty()) {
            views = seedDefaultViews(admin.getId());
        }
        return ResponseEntity.ok(views);
    }

    @PostMapping("/views")
    public ResponseEntity<AdminSavedLogView> createView(@RequestBody SavedViewBody body,
                                                        @AuthenticationPrincipal User admin) {
        return ResponseEntity.status(201).body(savedViewRepository.save(AdminSavedLogView.builder()
                .adminId(admin.getId()).name(body.name().trim()).query(body.query().trim())
                .build()));
    }

    @DeleteMapping("/views/{id}")
    public ResponseEntity<Void> deleteView(@PathVariable UUID id,
                                           @AuthenticationPrincipal User admin) {
        AdminSavedLogView view = savedViewRepository.findById(id)
                .filter(v -> v.getAdminId().equals(admin.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("SavedLogView", "id", id));
        savedViewRepository.delete(view);
        return ResponseEntity.noContent().build();
    }

    // ── alert rules (A8) + firings (A9) ─────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<List<LogAlertRule>> alerts() {
        return ResponseEntity.ok(alertRuleRepository.findAll());
    }

    @PostMapping("/alerts")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LogAlertRule> createAlert(@RequestBody AlertRuleBody body,
                                                    @AuthenticationPrincipal User admin) {
        LogAlertRule.RuleKind kind = parseKind(body.kind());
        LogAlertRule rule = LogAlertRule.builder()
                .kind(kind)
                .name(body.name() != null && !body.name().isBlank() ? body.name() : kind.name())
                .threshold(body.threshold() != null ? body.threshold() : 10)
                .windowMinutes(body.windowMinutes() != null ? body.windowMinutes() : 60)
                .severity(parseSeverity(body.severity()))
                .enabled(body.enabled() == null || body.enabled())
                .createdBy(admin.getId())
                .build();
        adminAuditor.record(AuditOperation.CREATE, "LogAlertRule", null,
                "ADMIN_LOG_ALERT_RULE_CREATE", kind.name());
        return ResponseEntity.status(201).body(alertRuleRepository.save(rule));
    }

    @PatchMapping("/alerts/{id}")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LogAlertRule> updateAlert(@PathVariable UUID id,
                                                    @RequestBody AlertRuleBody body) {
        LogAlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LogAlertRule", "id", id));
        if (body.name() != null && !body.name().isBlank()) rule.setName(body.name());
        if (body.threshold() != null) rule.setThreshold(body.threshold());
        if (body.windowMinutes() != null) rule.setWindowMinutes(body.windowMinutes());
        if (body.severity() != null) rule.setSeverity(parseSeverity(body.severity()));
        if (body.enabled() != null) rule.setEnabled(body.enabled());
        adminAuditor.record(AuditOperation.UPDATE, "LogAlertRule", id,
                "ADMIN_LOG_ALERT_RULE_UPDATE", rule.getKind().name());
        return ResponseEntity.ok(alertRuleRepository.save(rule));
    }

    @DeleteMapping("/alerts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteAlert(@PathVariable UUID id) {
        alertRuleRepository.deleteById(id);
        adminAuditor.record(AuditOperation.DELETE, "LogAlertRule", id,
                "ADMIN_LOG_ALERT_RULE_DELETE");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alerts/firings")
    public ResponseEntity<Page<LogAlertFiring>> firings(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) UUID ruleId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(alertFiringRepository.feed(since, ruleId,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    // ── retention board (A10) ───────────────────────────────────────────

    @GetMapping("/retention")
    public ResponseEntity<List<Map<String, Object>>> retention() {
        List<Map<String, Object>> board = new ArrayList<>();
        board.add(store("audit_log_by_user / by_resource", "180d table TTL",
                "AuditSchemaInitializer (applied at startup)", null));
        board.add(store("settings_audit", "2y", "RetentionSweepJob 03:20",
                count(settingsAuditRepository::count)));
        board.add(store("login_events", "1y", "RetentionSweepJob 03:20",
                count(loginEventRepository::count)));
        board.add(store("otp_challenges", "180d past expiry", "RetentionSweepJob 03:20", null));
        board.add(store("consent_events", "kept for account life (+post-purge — compliance evidence)",
                "deliberate keep", null));
        board.add(store("reports / user_strikes", "kept ≥2y (moderation evidence; strikes never deleted)",
                "deliberate keep", count(reportRepository::count)));
        board.add(store("export_jobs + ZIP files", "48h past readiness",
                "RetentionSweepJob 03:20 (+ immediate delete on account purge)", null));
        board.add(store("dead_letters (DLQ parking lot)", "90d", "RetentionSweepJob 03:20", null));
        board.add(store("job_runs", "90d", "NotificationCleanupJob 03:15", null));
        board.add(store("notifications (read)", "90d", "NotificationCleanupJob 03:15", null));
        return ResponseEntity.ok(board);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private List<AdminSavedLogView> seedDefaultViews(UUID adminId) {
        List<AdminSavedLogView> defaults = List.of(
                view(adminId, "Failed logins 24h", "store:login outcome:FAILED since:24h"),
                view(adminId, "Admin actions 7d", "store:audit path:/api/v1/admin/* since:7d"),
                view(adminId, "Settings churn 24h", "store:settings since:24h"),
                view(adminId, "Reports today", "store:reports since:24h"),
                view(adminId, "DLQ today", "store:dlq since:24h"));
        return savedViewRepository.saveAll(defaults);
    }

    private static AdminSavedLogView view(UUID adminId, String name, String query) {
        return AdminSavedLogView.builder().adminId(adminId).name(name).query(query).build();
    }

    private ExploreQuery resolveUsernameAnchor(ExploreQuery query) {
        if (query.usernameAnchor() == null) return query;
        UUID resolved = userRepository.findByUsernameAndDeletedAtIsNull(query.usernameAnchor())
                .map(User::getId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username",
                        query.usernameAnchor()));
        return query.withUserId(resolved);
    }

    private static Map<String, Object> row(String store, LocalDateTime at, String summary,
                                           Map<String, String> fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store", store);
        row.put("at", at);
        row.put("summary", summary);
        row.put("fields", fields);
        return row;
    }

    private static LocalDateTime toLdt(java.time.Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Long count(java.util.function.LongSupplier counter) {
        try {
            return counter.getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> store(String name, String policy, String enforcedBy, Long rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", name);
        m.put("policy", policy);
        m.put("enforcedBy", enforcedBy);
        if (rows != null) m.put("rows", rows);
        return m;
    }

    private static LogAlertRule.RuleKind parseKind(String raw) {
        try {
            return LogAlertRule.RuleKind.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown rule kind. Allowed: "
                    + java.util.Arrays.toString(LogAlertRule.RuleKind.values()), "INVALID_RULE_KIND");
        }
    }

    private static LogAlertRule.Severity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return LogAlertRule.Severity.MEDIUM;
        try {
            return LogAlertRule.Severity.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown severity. Allowed: INFO, MEDIUM, HIGH.",
                    "INVALID_SEVERITY");
        }
    }

    /**
     * OTP telemetry, aggregate-only (users-roles.md §5): volume + outcome mix
     * per purpose and hot destination hashes. Codes and destinations are never
     * exposed — the hash is only a correlator for "same target hammered".
     */
    @GetMapping("/otp-stats")
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> otpStats(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "24") int windowHours,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "5") int burstThreshold) {
        java.time.LocalDateTime since = java.time.LocalDateTime.now()
                .minusHours(Math.max(1, Math.min(windowHours, 24 * 30)));
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("windowHours", Math.max(1, Math.min(windowHours, 24 * 30)));
        java.util.List<java.util.Map<String, Object>> byPurpose = new java.util.ArrayList<>();
        for (Object[] row : otpChallengeRepository.statsByPurposeSince(since)) {
            byPurpose.add(java.util.Map.of(
                    "purpose", String.valueOf(row[0]),
                    "issued", ((Number) row[1]).longValue(),
                    "consumed", ((Number) row[2]).longValue(),
                    "maxedAttempts", ((Number) row[3]).longValue()));
        }
        body.put("byPurpose", byPurpose);
        java.util.List<java.util.Map<String, Object>> hot = new java.util.ArrayList<>();
        for (Object[] row : otpChallengeRepository.hotDestinationsSince(
                since, Math.max(2, burstThreshold))) {
            if (hot.size() >= 50) break;
            hot.add(java.util.Map.of(
                    "destinationHash", String.valueOf(row[0]),
                    "challenges", ((Number) row[1]).longValue()));
        }
        body.put("hotDestinations", hot);
        body.put("note", "Aggregate-only: codes and raw destinations are never stored or "
                + "shown; destinationHash is HMAC'd and useful only as a correlator.");
        return org.springframework.http.ResponseEntity.ok(body);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        String cleaned = s.replace("\"", "\"\"").replace("\n", " ");
        return cleaned.contains(",") ? "\"" + cleaned + "\"" : cleaned;
    }
}
