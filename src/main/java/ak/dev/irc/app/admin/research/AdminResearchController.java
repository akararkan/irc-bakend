package ak.dev.irc.app.admin.research;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.research.cassandra.service.CassandraResearchEngagementService;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.service.ResearchService;
import ak.dev.irc.app.user.service.NotificationService;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin research oversight (blueprint §3.4, research-qna.md §4). Research had
 * NO admin ownership bypass — every mutation here reuses the owner-path
 * service transitions by acting with the owner's id, so cache eviction, ES
 * removal, trending untag, and S3 cleanup stay byte-identical.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/research")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: moderation parts of research are MODERATOR RW
public class AdminResearchController {

    private final ResearchRepository researchRepository;
    private final ResearchService researchService;
    private final CassandraResearchEngagementService engagementService;
    private final ResearchFlagRepository flagRepository;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;
    private final NotificationService notificationService;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminResearchRow(UUID id, String title, String ircId, String status,
                                   String visibility, UUID researcherId, String researcherUsername,
                                   Long viewCount, Long downloadCount, Long reactionCount,
                                   Long commentCount, Long citationCount,
                                   LocalDateTime scheduledPublishAt, LocalDateTime publishedAt,
                                   LocalDateTime createdAt) {
    }

    public record ReasonBody(@Size(max = 500) String reason) {
    }

    public record FlagBody(ResearchFlag.FlagType type, @Size(max = 500) String note) {
    }

    // ── reads ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<AdminResearchRow>> browse(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID researcherId,
            @RequestParam(required = false) String ircId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 25) Pageable pageable) {
        ResearchStatus parsed = parseStatus(status);
        Page<Research> page = researchRepository.adminBrowse(parsed, researcherId,
                blankToNull(ircId), blankToNull(q), clamp(pageable));
        return ResponseEntity.ok(page.map(AdminResearchController::toRow));
    }

    @GetMapping("/top")
    public ResponseEntity<Page<AdminResearchRow>> top(
            @RequestParam(defaultValue = "downloads") String by,
            @PageableDefault(size = 25) Pageable pageable) {
        Page<Research> page = switch (by.trim().toLowerCase()) {
            case "downloads" -> researchRepository.topByDownloads(clamp(pageable));
            case "citations" -> researchRepository.topByCitations(clamp(pageable));
            default -> throw new BadRequestException(
                    AdminContentMessages.INVALID_SORT_MSG, AdminContentMessages.INVALID_SORT);
        };
        return ResponseEntity.ok(page.map(AdminResearchController::toRow));
    }

    @GetMapping("/flags")
    public ResponseEntity<Page<ResearchFlag>> openFlags(@PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(flagRepository.findByResolvedAtIsNullOrderByCreatedAtAsc(clamp(pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        // toRow reads researcher.getUsername(); with open-in-view off the plain
        // by-id load hands back a detached proxy and the mapper throws.
        Research r = researchRepository.findByIdWithResearcher(id)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "id", id));
        return ResponseEntity.ok(Map.of(
                "research", toRow(r),
                "slug", String.valueOf(r.getSlug()),
                "abstractText", String.valueOf(r.getAbstractText()),
                "saveCount", r.getSaveCount() == null ? 0L : r.getSaveCount(),
                "shareCount", r.getShareCount() == null ? 0L : r.getShareCount(),
                "commentsEnabled", r.isCommentsEnabled(),
                "downloadsEnabled", r.isDownloadsEnabled(),
                "flags", flagRepository.findByResearchIdOrderByCreatedAtDesc(id)));
    }

    /** First HTTP surface over the (previously unreferenced) download log. */
    @GetMapping("/{id}/downloads")
    public ResponseEntity<List<Map<String, Object>>> downloads(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {
        require(id);
        List<Map<String, Object>> rows = engagementService
                .recentDownloads(id, Pages.clamp(limit)).stream()
                .map(d -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("downloadId", d.getDownloadId());
                    m.put("userId", d.getUserId());
                    m.put("mediaId", d.getMediaId());
                    m.put("ipAddress", d.getIpAddress());
                    m.put("createdAt", d.getCreatedAt());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(rows);
    }

    // ── mutations ───────────────────────────────────────────────────────

    @PostMapping("/{id}/unpublish")
    @RequiresStepUp
    public ResponseEntity<Void> unpublish(@PathVariable UUID id,
                                          @RequestBody(required = false) ReasonBody body) {
        Research r = require(id);
        researchService.unpublish(id, r.getResearcher().getId());
        record(r, "ADMIN_RESEARCH_UNPUBLISH", reason(body));
        notifyOwner(r, AdminContentMessages.NOTIF_RESEARCH_UNPUBLISHED_TITLE,
                AdminContentMessages.NOTIF_RESEARCH_UNPUBLISHED_BODY
                        .formatted(r.getTitle(), suffix(body)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retract")
    @RequiresStepUp
    public ResponseEntity<Void> retract(@PathVariable UUID id,
                                        @RequestBody(required = false) ReasonBody body) {
        Research r = require(id);
        researchService.retract(id, r.getResearcher().getId());
        record(r, "ADMIN_RESEARCH_RETRACT", reason(body));
        notifyOwner(r, AdminContentMessages.NOTIF_RESEARCH_RETRACTED_TITLE,
                AdminContentMessages.NOTIF_RESEARCH_RETRACTED_BODY
                        .formatted(r.getTitle(), suffix(body)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestBody(required = false) ReasonBody body) {
        Research r = require(id);
        UUID ownerId = r.getResearcher().getId();
        String title = r.getTitle();
        record(r, "ADMIN_RESEARCH_DELETE", reason(body));
        researchService.delete(id, ownerId);
        notifyUser(ownerId, AdminContentMessages.NOTIF_RESEARCH_REMOVED_TITLE,
                AdminContentMessages.NOTIF_RESEARCH_REMOVED_BODY.formatted(title, suffix(body)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/flags")
    public ResponseEntity<ResearchFlag> flag(@PathVariable UUID id, @RequestBody FlagBody body) {
        require(id);
        if (body.type() == null) {
            throw new BadRequestException(AdminContentMessages.INVALID_INPUT_FLAG_TYPE_MSG,
                    AdminContentMessages.INVALID_INPUT);
        }
        ResearchFlag saved = flagRepository.save(ResearchFlag.builder()
                .researchId(id)
                .type(body.type())
                .note(body.note())
                .flaggedBy(ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null))
                .build());
        adminAuditor.record(AuditOperation.CREATE, "Research", id,
                "ADMIN_RESEARCH_FLAG", body.type() + (body.note() != null ? ": " + body.note() : ""));
        return ResponseEntity.status(201).body(saved);
    }

    @PostMapping("/flags/{flagId}/resolve")
    public ResponseEntity<ResearchFlag> resolveFlag(@PathVariable UUID flagId,
                                                    @RequestBody(required = false) ReasonBody body) {
        ResearchFlag flag = flagRepository.findById(flagId)
                .orElseThrow(() -> new ResourceNotFoundException("ResearchFlag", "id", flagId));
        flag.setResolvedAt(LocalDateTime.now());
        flag.setResolutionNote(body != null ? body.reason() : null);
        adminAuditor.record(AuditOperation.UPDATE, "Research", flag.getResearchId(),
                "ADMIN_RESEARCH_FLAG_RESOLVE");
        return ResponseEntity.ok(flagRepository.save(flag));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Research require(UUID id) {
        return researchRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Research", "id", id));
    }

    private void record(Research r, String action, String reason) {
        moderationRecorder.decision("RESEARCH", r.getId().toString(), action, reason, null,
                "ircId=" + r.getIrcId());
        adminAuditor.record(AuditOperation.UPDATE, "Research", r.getId(), action, reason);
    }

    private void notifyOwner(Research r, String title, String body) {
        notifyUser(r.getResearcher().getId(), title, body);
    }

    private void notifyUser(UUID userId, String title, String body) {
        try {
            notificationService.sendSystemNotification(userId, title, body);
        } catch (Exception e) {
            log.debug("[ADMIN-RESEARCH] owner notification skipped: {}", e.getMessage());
        }
    }

    private static AdminResearchRow toRow(Research r) {
        return new AdminResearchRow(r.getId(), r.getTitle(), r.getIrcId(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getVisibility() != null ? r.getVisibility().name() : null,
                r.getResearcher() != null ? r.getResearcher().getId() : null,
                r.getResearcher() != null ? r.getResearcher().getUsername() : null,
                r.getViewCount(), r.getDownloadCount(), r.getReactionCount(),
                r.getCommentCount(), r.getCitationCount(),
                r.getScheduledPublishAt(), r.getPublishedAt(), r.getCreatedAt());
    }

    private static ResearchStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ResearchStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(AdminContentMessages.INVALID_STATUS_MSG.formatted(
                    java.util.Arrays.toString(ResearchStatus.values())),
                    AdminContentMessages.INVALID_STATUS);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String reason(ReasonBody body) {
        return body != null ? body.reason() : null;
    }

    private static String suffix(ReasonBody body) {
        return body != null && body.reason() != null && !body.reason().isBlank()
                ? " (" + body.reason() + ")" : "";
    }

    private static Pageable clamp(Pageable pageable) {
        return PageRequest.of(Math.max(0, pageable.getPageNumber()),
                Pages.clamp(pageable.getPageSize()));
    }
}
