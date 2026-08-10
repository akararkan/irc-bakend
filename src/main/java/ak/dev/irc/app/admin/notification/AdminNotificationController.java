package ak.dev.irc.app.admin.notification;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.common.notification.job.TrendingNotificationJob;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.email.EmailSendLog;
import ak.dev.irc.app.email.EmailSendLogRepository;
import ak.dev.irc.app.email.EmailService;
import ak.dev.irc.app.settings.notification.repository.PushTokenRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.repository.NotificationRepository;
import jakarta.validation.constraints.NotBlank;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notifications & email administration (blueprint §3.8,
 * notifications-email.md §4): volume/read-rate stats, the types registry, the
 * announcement composer (dry-run first — the most abusable tool in the
 * dashboard), the manual digest trigger, the email send-ledger health strip,
 * an admin-owned test email, and stale push-token purge.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {

    private final NotificationRepository notificationRepository;
    private final AnnouncementService announcementService;
    private final PlatformAnnouncementRepository announcementRepository;
    private final TrendingNotificationJob trendingNotificationJob;
    private final EmailSendLogRepository emailSendLogRepository;
    private final EmailService emailService;
    private final PushTokenRepository pushTokenRepository;
    private final AdminAuditor adminAuditor;

    public record AnnouncementBody(@NotBlank String title, @NotBlank String body,
                                   String audienceRole, Integer activeSinceDays,
                                   Boolean dryRun, Boolean confirmLargeAudience,
                                   String audienceLanguage, String scheduledAt) {
    }

    public record TestEmailBody(@NotBlank String subject, @NotBlank String body) {
    }

    // ── stats + registry ────────────────────────────────────────────────

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','ANALYST')")   // §6 matrix: notifications RO
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime t = to == null ? LocalDateTime.now() : to;
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Object[] row : notificationRepository.volumeAndReadByTypeBetween(f, t)) {
            long total = ((Number) row[1]).longValue();
            long read = row[2] == null ? 0 : ((Number) row[2]).longValue();
            byType.add(Map.of(
                    "type", String.valueOf(row[0]),
                    "created", total,
                    "read", read,
                    "readRatePct", total == 0 ? 0.0 : Math.round(read * 1000.0 / total) / 10.0));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", f);
        body.put("to", t);
        body.put("byType", byType);
        body.put("note", AdminOpsMessages.NOTE_NOTIF_STATS_LEGACY_INBOX);
        return ResponseEntity.ok(body);
    }

    /** Static registry of every NotificationType with its delivery metadata. */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> types() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type.name());
            row.put("category", NotificationCategory.of(type).name());
            NotificationKind kind = kindOf(type.name());
            if (kind != null) {
                row.put("prefCategory", kind.prefCategory().name());
                row.put("aggregable", kind.aggregable());
                row.put("emailEligible", kind.emailEligible());
            } else {
                row.put("prefCategory", null);
                row.put("note", AdminOpsMessages.NOTE_NO_NOTIFICATION_KIND);
            }
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    // ── announcements ───────────────────────────────────────────────────

    @PostMapping("/announcements")
    @RequiresStepUp
    public ResponseEntity<AnnouncementService.ComposeResult> announce(
            @RequestBody AnnouncementBody body) {
        ak.dev.irc.app.user.enums.Role role = null;
        if (body.audienceRole() != null && !body.audienceRole().isBlank()) {
            role = ak.dev.irc.app.user.enums.Role.valueOf(body.audienceRole().trim().toUpperCase());
        }
        ak.dev.irc.app.common.enums.Language language = null;
        if (body.audienceLanguage() != null && !body.audienceLanguage().isBlank()) {
            try {
                language = ak.dev.irc.app.common.enums.Language.valueOf(
                        body.audienceLanguage().trim().toUpperCase());
            } catch (Exception e) {
                throw new ak.dev.irc.app.common.exception.BadRequestException(
                        AdminOpsMessages.INVALID_LANGUAGE_MSG.formatted(java.util.Arrays.toString(
                                ak.dev.irc.app.common.enums.Language.values())),
                        AdminOpsMessages.INVALID_LANGUAGE);
            }
        }
        java.time.LocalDateTime scheduledAt = null;
        if (body.scheduledAt() != null && !body.scheduledAt().isBlank()) {
            try {
                scheduledAt = java.time.LocalDateTime.parse(body.scheduledAt().trim());
            } catch (Exception e) {
                throw new ak.dev.irc.app.common.exception.BadRequestException(
                        AdminOpsMessages.INVALID_SCHEDULE_FORMAT_MSG,
                        AdminOpsMessages.INVALID_SCHEDULE);
            }
        }
        AnnouncementService.ComposeResult result = announcementService.compose(
                new AnnouncementService.ComposeRequest(body.title(), body.body(), role,
                        body.activeSinceDays(),
                        Boolean.TRUE.equals(body.dryRun()),
                        Boolean.TRUE.equals(body.confirmLargeAudience()),
                        language, scheduledAt));
        return result.dryRun()
                ? ResponseEntity.ok(result)
                : ResponseEntity.accepted().body(result);
    }

    /** Cancel a SCHEDULED announcement before its sweep fires it. */
    @DeleteMapping("/announcements/{id}")
    @RequiresStepUp
    public ResponseEntity<java.util.Map<String, Object>> cancelAnnouncement(
            @PathVariable java.util.UUID id) {
        announcementService.cancelScheduled(id);
        return ResponseEntity.ok(java.util.Map.of("id", id, "status", "CANCELLED"));
    }

    @GetMapping("/announcements")
    public ResponseEntity<Page<PlatformAnnouncement>> announcements(
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(announcementRepository.browse(
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    // ── digest ──────────────────────────────────────────────────────────

    /**
     * Manual TRENDING_DIGEST fire. Honest caveat: the per-day cap is enforced
     * on the EMAIL leg (Redis claim); a same-day re-run still inserts a second
     * inbox row because the kind is non-aggregable.
     */
    @PostMapping("/digest/run")
    public ResponseEntity<Void> runDigest() {
        trendingNotificationJob.runNow();
        adminAuditor.record(AuditOperation.OTHER, "Notification", null, "ADMIN_DIGEST_RUN");
        return ResponseEntity.accepted().build();
    }

    // ── email health / test ─────────────────────────────────────────────

    @GetMapping("/email/stats")
    public ResponseEntity<Map<String, Object>> emailStats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "25") int tail) {
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(7) : from;
        LocalDateTime t = to == null ? LocalDateTime.now() : to;
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        for (EmailSendLog.Outcome o : EmailSendLog.Outcome.values()) {
            byOutcome.put(o.name(), 0L);
        }
        for (Object[] row : emailSendLogRepository.countByOutcomeBetween(f, t)) {
            byOutcome.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", f);
        body.put("to", t);
        body.put("enabled", emailService.isEnabled());
        body.put("byOutcome", byOutcome);
        body.put("ledgerTail", emailSendLogRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(0, Pages.clamp(tail))).getContent());
        return ResponseEntity.ok(body);
    }

    /** Sends a test email to the calling admin's own address only. */
    @PostMapping("/email/test")
    public ResponseEntity<Map<String, Object>> testEmail(@RequestBody TestEmailBody body,
                                                         @AuthenticationPrincipal User admin) {
        emailService.sendAsync(admin.getEmail(),
                AdminOpsMessages.NOTIF_TEST_EMAIL_SUBJECT.formatted(body.subject()),
                body.body(), "<p>" + body.body() + "</p>");
        adminAuditor.record(AuditOperation.OTHER, "Email", null,
                "ADMIN_EMAIL_TEST", "to self (" + admin.getId() + ")");
        return ResponseEntity.accepted().body(Map.of(
                "queuedTo", admin.getEmail(), "enabled", emailService.isEnabled()));
    }

    // ── push tokens ─────────────────────────────────────────────────────

    @DeleteMapping("/push-tokens/{id}")
    public ResponseEntity<Void> purgePushToken(@PathVariable UUID id) {
        pushTokenRepository.deleteById(id);
        adminAuditor.record(AuditOperation.DELETE, "PushToken", id, "ADMIN_PUSH_TOKEN_PURGE");
        return ResponseEntity.noContent().build();
    }

    private static NotificationKind kindOf(String name) {
        try {
            return NotificationKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
