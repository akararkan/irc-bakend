package ak.dev.irc.app.admin.safety;

import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.entity.ReportEvidence;
import ak.dev.irc.app.settings.safety.entity.UserStrike;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.enums.Resolution;
import ak.dev.irc.app.settings.safety.repository.ReportEvidenceRepository;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.settings.safety.repository.UserStrikeRepository;
import ak.dev.irc.app.settings.safety.service.StrikeService;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserRestrictionRepository;
import ak.dev.irc.app.user.service.NotificationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The moderator console over the report state machine (blueprint §3.6,
 * safety-reports.md): grouped triage queue, workbench detail with frozen
 * evidence, the transition endpoints, the strikes ledger (first caller of
 * {@code StrikeService.issueStrike}), the per-user moderation record, the
 * read-only consent viewer, and block/restrict aggregates.
 * <p>
 * The coarse-outcome rule holds: {@code resolution}, moderator notes, and
 * reporter identities render ONLY inside this console.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/safety")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: safety triage is MODERATOR RW; SUPPORT gets intake reads below
public class AdminSafetyController {

    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository evidenceRepository;
    private final ReportModerationService moderationService;
    private final StrikeService strikeService;
    private final UserStrikeRepository strikeRepository;
    private final ConsentService consentService;
    private final UserBlockRepository blockRepository;
    private final UserRestrictionRepository restrictionRepository;
    private final NotificationService notificationService;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminReportRow(UUID id, UUID reporterId, String targetType, String targetRef,
                                 String reason, String state, String resolution, String groupKey,
                                 String details, String moderatorNote,
                                 UUID triagedBy, LocalDateTime triagedAt,
                                 UUID actionedBy, LocalDateTime actedAt,
                                 LocalDateTime createdAt) {

        static AdminReportRow of(Report r) {
            return new AdminReportRow(r.getId(), r.getReporterId(),
                    r.getTargetType() != null ? r.getTargetType().name() : null,
                    r.targetRefOrId(),
                    r.getReason() != null ? r.getReason().name() : null,
                    r.getState() != null ? r.getState().name() : null,
                    r.getResolution() != null ? r.getResolution().name() : null,
                    r.getGroupKey(), r.getDetails(), r.getModeratorNote(),
                    r.getTriagedBy(), r.getTriagedAt(), r.getActionedBy(), r.getActedAt(),
                    r.getCreatedAt());
        }
    }

    public record NoteBody(@Size(max = 1000) String note, Boolean wholeGroup) {
    }

    public record ActionBody(@NotBlank String resolution, @Size(max = 1000) String note,
                             Boolean issueStrike, @Size(max = 200) String strikeReason,
                             Boolean wholeGroup) {
    }

    public record StrikeBody(UUID reportId, @NotBlank @Size(max = 200) String reason) {
    }

    // ── queue + detail ──────────────────────────────────────────────────

    /** Grouped triage queue — one row per (target, reason) group, oldest first. */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")   // SUPPORT: report-intake view (read)
    public ResponseEntity<List<Map<String, Object>>> queue(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        List<String> states = state == null || state.isBlank()
                ? List.of(ReportState.SUBMITTED.name(), ReportState.TRIAGED.name())
                : List.of(parseState(state).name());
        int size = Pages.clamp(pageSize);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] g : reportRepository.findOpenGroups(states,
                targetType == null || targetType.isBlank() ? null : targetType.trim().toUpperCase(),
                reason == null || reason.isBlank() ? null : reason.trim().toUpperCase(),
                size, page * size)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupKey", g[0]);
            row.put("targetType", g[1]);
            row.put("targetRef", g[2]);
            row.put("reason", g[3]);
            row.put("reporterCount", ((Number) g[4]).longValue());
            row.put("reportCount", ((Number) g[5]).longValue());
            row.put("firstSeen", String.valueOf(g[6]));
            row.put("lastSeen", String.valueOf(g[7]));
            row.put("state", g[8]);
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    /** Workbench detail: the report, its group siblings, and the frozen evidence. */
    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT')")   // SUPPORT: report-intake view (read)
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        Report r = reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report", "id", id));
        List<AdminReportRow> siblings = reportRepository
                .findByGroupKeyOrderByCreatedAtAsc(r.getGroupKey()).stream()
                .map(AdminReportRow::of)
                .toList();
        ReportEvidence evidence = evidenceRepository.findByGroupKey(r.getGroupKey()).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report", AdminReportRow.of(r));
        body.put("group", siblings);
        body.put("evidence", evidence);
        body.put("linkedStrikes", strikeRepository.findByReportId(id));
        return ResponseEntity.ok(body);
    }

    // ── transitions ─────────────────────────────────────────────────────

    @PostMapping("/reports/{id}/triage")
    public ResponseEntity<AdminReportRow> triage(@PathVariable UUID id,
                                                 @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(AdminReportRow.of(moderationService.triage(id,
                body != null ? body.note() : null, wholeGroup(body))));
    }

    @PostMapping("/reports/{id}/dismiss")
    public ResponseEntity<AdminReportRow> dismiss(@PathVariable UUID id,
                                                  @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(AdminReportRow.of(moderationService.dismiss(id,
                body != null ? body.note() : null, wholeGroup(body))));
    }

    @PostMapping("/reports/{id}/action")
    @RequiresStepUp
    public ResponseEntity<AdminReportRow> action(@PathVariable UUID id, @RequestBody ActionBody body) {
        Resolution resolution = parseResolution(body.resolution());
        return ResponseEntity.ok(AdminReportRow.of(moderationService.action(id, resolution,
                body.note(), Boolean.TRUE.equals(body.issueStrike()), body.strikeReason(),
                body.wholeGroup() == null || body.wholeGroup())));
    }

    @PostMapping("/appeals/{reportId}/uphold")
    @RequiresStepUp
    public ResponseEntity<AdminReportRow> uphold(@PathVariable UUID reportId,
                                                 @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(AdminReportRow.of(
                moderationService.uphold(reportId, body != null ? body.note() : null)));
    }

    @PostMapping("/appeals/{reportId}/reverse")
    @RequiresStepUp
    public ResponseEntity<AdminReportRow> reverse(@PathVariable UUID reportId,
                                                  @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(AdminReportRow.of(
                moderationService.reverse(reportId, body != null ? body.note() : null)));
    }

    // ── strikes ─────────────────────────────────────────────────────────

    /** First caller of the write-dead {@code StrikeService.issueStrike}. */
    @PostMapping("/users/{userId}/strikes")
    @RequiresStepUp
    public ResponseEntity<UserStrike> issueStrike(@PathVariable UUID userId,
                                                  @RequestBody StrikeBody body) {
        UserStrike strike = strikeService.issueStrike(userId, body.reportId(), body.reason());
        adminAuditor.record(AuditOperation.CREATE, "User", userId,
                "ADMIN_STRIKE_ISSUE", body.reason());
        try {
            notificationService.sendSystemNotification(userId, "Account warning",
                    "A moderation strike was recorded on your account: " + body.reason()
                            + ". Strikes expire automatically after 90 days.");
        } catch (Exception e) {
            log.debug("[ADMIN-SAFETY] strike notification skipped: {}", e.getMessage());
        }
        return ResponseEntity.status(201).body(strike);
    }

    @DeleteMapping("/strikes/{strikeId}")
    @RequiresStepUp
    public ResponseEntity<Void> revokeStrike(@PathVariable UUID strikeId,
                                             @RequestBody(required = false) NoteBody body) {
        moderationService.revokeStrike(strikeId, body != null ? body.note() : null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/strikes")
    public ResponseEntity<Page<UserStrike>> strikes(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 25) Pageable pageable) {
        Pageable clamped = PageRequest.of(Math.max(0, pageable.getPageNumber()),
                Pages.clamp(pageable.getPageSize()));
        Page<UserStrike> page = userId != null
                ? strikeRepository.findByUserIdOrderByIssuedAtDesc(userId, clamped)
                : strikeRepository.findAllByOrderByIssuedAtDesc(clamped);
        if (active != null) {
            List<UserStrike> filtered = page.getContent().stream()
                    .filter(s -> s.isActive() == active)
                    .toList();
            return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(
                    filtered, clamped, page.getTotalElements()));
        }
        return ResponseEntity.ok(page);
    }

    // ── record / consent / block stats / analytics ──────────────────────

    /** The 360° per-user moderation record (workbench right pane). */
    @GetMapping("/users/{userId}/record")
    public ResponseEntity<Map<String, Object>> userRecord(@PathVariable UUID userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeStrikes", strikeRepository.countActive(userId, LocalDateTime.now()));
        body.put("strikes", strikeRepository.findByUserIdOrderByIssuedAtDesc(userId));
        body.put("reportsAgainst", reportRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        ak.dev.irc.app.settings.safety.enums.ReportTargetType.USER,
                        userId, PageRequest.of(0, 50))
                .map(AdminReportRow::of).getContent());
        body.put("reportsFiled", reportRepository
                .findByReporterIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .map(AdminReportRow::of).getContent());
        return ResponseEntity.ok(body);
    }

    /** Read-only compliance evidence — never editable from any admin surface. */
    @GetMapping("/users/{userId}/consent")
    public ResponseEntity<?> consent(@PathVariable UUID userId,
                                     @RequestParam(required = false) String scope,
                                     @PageableDefault(size = 25) Pageable pageable) {
        if (scope != null && !scope.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "scope", scope.trim().toUpperCase(),
                    "granted", consentService.currentState(userId, scope)));
        }
        return ResponseEntity.ok(consentService.history(userId,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    @GetMapping("/stats/blocks")
    public ResponseEntity<Map<String, Object>> blockStats(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int top) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 365)));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (Object[] row : blockRepository.blocksPerDay(from)) {
            perDay.add(Map.of("day", String.valueOf(row[0]),
                    "count", ((Number) row[1]).longValue()));
        }
        List<Map<String, Object>> mostBlocked = new ArrayList<>();
        for (Object[] row : blockRepository.topBlockedUsers(Pages.clamp(top))) {
            mostBlocked.add(Map.of("userId", row[0],
                    "inboundBlocks", ((Number) row[1]).longValue()));
        }
        List<Map<String, Object>> restrictionsPerDay = new ArrayList<>();
        for (Object[] row : restrictionRepository.restrictionsPerDay(from)) {
            restrictionsPerDay.add(Map.of("day", String.valueOf(row[0]),
                    "count", ((Number) row[1]).longValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalBlocks", blockRepository.countAllBlocks());
        body.put("totalRestrictions", restrictionRepository.countAllRestrictions());
        body.put("blocksPerDay", perDay);
        body.put("restrictionsPerDay", restrictionsPerDay);
        body.put("mostBlockedUsers", mostBlocked);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> analytics(
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to) {
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime t = to == null ? LocalDateTime.now() : to;

        Map<String, Long> byReason = new LinkedHashMap<>();
        for (Object[] row : reportRepository.countByReasonBetween(f, t)) {
            byReason.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> byState = new LinkedHashMap<>();
        for (Object[] row : reportRepository.countGroupedByState()) {
            byState.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long open = reportRepository.countByStateIn(
                EnumSet.of(ReportState.SUBMITTED, ReportState.TRIAGED));
        long appealed = reportRepository.countByState(ReportState.APPEALED);
        long upheld = reportRepository.countByState(ReportState.UPHELD);
        long reversed = reportRepository.countByState(ReportState.REVERSED);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", f);
        body.put("to", t);
        body.put("openQueue", open);
        body.put("appealBacklog", appealed);
        body.put("byReason", byReason);
        body.put("byState", byState);
        body.put("reversalRate", (upheld + reversed) == 0 ? 0.0
                : Math.round(reversed * 1000.0 / (upheld + reversed)) / 10.0);
        body.put("strikesActive", strikeRepository.countAllActive(LocalDateTime.now()));
        body.put("strikesIssued30d", strikeRepository.countIssuedSince(LocalDateTime.now().minusDays(30)));
        return ResponseEntity.ok(body);
    }

    // ── parse helpers ───────────────────────────────────────────────────

    private static boolean wholeGroup(NoteBody body) {
        return body == null || body.wholeGroup() == null || body.wholeGroup();
    }

    private static ReportState parseState(String raw) {
        try {
            return ReportState.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown state. Allowed: "
                    + java.util.Arrays.toString(ReportState.values()), "INVALID_STATE");
        }
    }

    private static Resolution parseResolution(String raw) {
        try {
            return Resolution.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown resolution. Allowed: "
                    + java.util.Arrays.toString(Resolution.values()), "INVALID_RESOLUTION");
        }
    }
}
