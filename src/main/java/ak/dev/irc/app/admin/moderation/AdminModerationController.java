package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.admin.content.AdminContentService;
import ak.dev.irc.app.admin.sound.AdminSoundService;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The unified moderation inbox (content-moderation.md §2.1): three feeders —
 * open content reports (grouped), FAILED_MODERATION media, and platform
 * keyword hits — flattened into one row shape; plus the capped bulk-action
 * endpoint over the singular moderation primitives.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: moderation queue/bulk is MODERATOR RW
public class AdminModerationController {

    private static final Set<String> CONTENT_TARGETS = Set.of("POST", "COMMENT", "STORY");

    private final ReportRepository reportRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final PlatformKeywordHitRepository keywordHitRepository;
    private final AdminContentService adminContentService;
    private final AdminSoundService adminSoundService;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueRow(String source, String targetType, String targetRef, String reason,
                           long reportCount, String state, LocalDateTime firstSeen,
                           LocalDateTime lastSeen) {
    }

    public record BulkTarget(@NotBlank String type, @NotBlank String id) {
    }

    public record BulkRequest(@NotBlank String action,
                              @NotNull @Size(min = 1, max = 100) List<BulkTarget> targets,
                              @Size(max = 500) String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkResult(String type, String id, String outcome, String error) {
    }

    // ── the queue ───────────────────────────────────────────────────────

    @GetMapping("/queue")
    public ResponseEntity<List<QueueRow>> queue(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        int size = Pages.clamp(pageSize);
        List<QueueRow> rows = new ArrayList<>();

        if (source == null || "reports".equalsIgnoreCase(source)) {
            List<String> openStates = List.of(ReportState.SUBMITTED.name(), ReportState.TRIAGED.name());
            for (Object[] g : reportRepository.findOpenGroups(openStates,
                    targetType == null ? null : targetType.toUpperCase(), null,
                    size, page * size)) {
                String type = String.valueOf(g[1]);
                if (targetType == null && !CONTENT_TARGETS.contains(type)) {
                    continue;   // default view is the content plane
                }
                rows.add(new QueueRow("reports", type, String.valueOf(g[2]),
                        String.valueOf(g[3]), ((Number) g[5]).longValue(), String.valueOf(g[8]),
                        toLdt(g[6]), toLdt(g[7])));
            }
        }

        if (source == null || "media".equalsIgnoreCase(source)) {
            mediaAssetRepository.findByStatusOrderByCreatedAtDesc(
                            MediaStatus.FAILED_MODERATION, PageRequest.of(page, size))
                    .forEach(m -> rows.add(new QueueRow("media", "MEDIA",
                            m.getId().toString(), "FAILED_MODERATION", 1,
                            m.getStatus().name(), m.getCreatedAt(), m.getUpdatedAt())));
        }

        if (source == null || "keywords".equalsIgnoreCase(source)) {
            keywordHitRepository.findByResolvedFalseOrderByCreatedAtAsc(PageRequest.of(page, size))
                    .forEach(h -> rows.add(new QueueRow("keywords", h.getTargetType(),
                            h.getTargetRef(), "KEYWORD:" + h.getKeywordNormalized(), 1,
                            "FLAGGED", h.getCreatedAt(), h.getCreatedAt())));
        }

        return ResponseEntity.ok(rows);
    }

    @PostMapping("/queue/keywords/{hitId}/resolve")
    public ResponseEntity<Void> resolveKeywordHit(@PathVariable UUID hitId) {
        keywordHitRepository.findById(hitId).ifPresent(h -> {
            h.setResolved(true);
            keywordHitRepository.save(h);
        });
        return ResponseEntity.noContent().build();
    }

    // ── bulk actions ────────────────────────────────────────────────────

    @PostMapping("/bulk")
    @RequiresStepUp
    public ResponseEntity<List<BulkResult>> bulk(@RequestBody BulkRequest request) {
        String action = request.action().trim().toUpperCase();
        List<BulkResult> results = new ArrayList<>();
        for (BulkTarget target : request.targets()) {
            try {
                UUID id = UUID.fromString(target.id());
                String type = target.type().trim().toUpperCase();
                switch (action) {
                    case "TAKEDOWN" -> {
                        requireType(type, "POST");
                        adminContentService.removePost(id, request.reason(), null);
                    }
                    case "RESTORE" -> {
                        requireType(type, "POST");
                        adminContentService.restorePost(id);
                    }
                    case "DELETE" -> {
                        switch (type) {
                            case "COMMENT" -> adminContentService.deleteComment(id, request.reason(), null);
                            case "STORY" -> adminContentService.deleteStory(id, request.reason(), null);
                            default -> throw new BadRequestException(
                                    AdminContentMessages.INVALID_BULK_TARGET_DELETE_MSG,
                                    AdminContentMessages.INVALID_BULK_TARGET);
                        }
                    }
                    case "SOUND_APPROVE" -> {
                        requireType(type, "SOUND");
                        adminSoundService.approve(id);
                    }
                    case "SOUND_REJECT" -> {
                        requireType(type, "SOUND");
                        adminSoundService.reject(id, request.reason(), null);
                    }
                    default -> throw new BadRequestException(
                            AdminContentMessages.INVALID_BULK_ACTION_MSG,
                            AdminContentMessages.INVALID_BULK_ACTION);
                }
                results.add(new BulkResult(target.type(), target.id(), "ok", null));
            } catch (Exception ex) {
                results.add(new BulkResult(target.type(), target.id(), "error", ex.getMessage()));
            }
        }
        long ok = results.stream().filter(r -> "ok".equals(r.outcome())).count();
        adminAuditor.record(AuditOperation.UPDATE, "Moderation", null, "ADMIN_MODERATION_BULK",
                action + " on " + request.targets().size() + " targets, ok=" + ok);
        return ResponseEntity.ok(results);
    }

    private static void requireType(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BadRequestException(
                    AdminContentMessages.INVALID_BULK_TARGET_NEEDS_MSG.formatted(expected),
                    AdminContentMessages.INVALID_BULK_TARGET);
        }
    }

    private static LocalDateTime toLdt(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.time.Instant i) return LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
        return null;
    }
}
