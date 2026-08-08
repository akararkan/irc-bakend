package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.entity.ModerationCaseField;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
import ak.dev.irc.app.moderation.repository.ModerationCaseFieldRepository;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import ak.dev.irc.app.moderation.service.ModerationMetricsService;
import ak.dev.irc.app.moderation.service.ModerationNotifier;
import ak.dev.irc.app.moderation.service.ModerationTrainingService;
import ak.dev.irc.app.security.SecurityUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The automated-moderation review queue (MODERATION_ROADMAP.md §12.1).
 *
 * <p>Distinct from {@code AdminModerationController}, which is the <em>reactive</em>
 * inbox: user reports, failed media, keyword hits. This one is the
 * <em>proactive</em> queue — content the classifier could not settle on its own,
 * which is the only queue that needs per-label scores, threshold bands and a
 * one-click "teach the model this was wrong" (§12.3).</p>
 *
 * <p>Mounted under the same {@code /api/v1/admin/moderation} prefix on distinct
 * sub-paths so it inherits the chain-level staff belt and the step-up
 * interceptor without any config change.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/moderation/review")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: moderation queue is MODERATOR RW
public class AdminAutoModerationController {

    private final ModerationCaseRepository caseRepository;
    private final ModerationCaseFieldRepository fieldRepository;
    private final ContentModerationService moderationService;
    private final ModerationTrainingService trainingService;
    private final ModerationMetricsService metricsService;
    private final ak.dev.irc.app.moderation.engine.ModerationSettingsService settings;
    private final AdminAuditor adminAuditor;

    // ── DTOs ────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueRow(UUID caseId, String entityType, String entityLabel, String entityRef,
                           String parentRef, UUID authorId, String status, String reasonCode,
                           String topLabel, Double topScore, String blocklistHit,
                           boolean slaBreached, String modelVersion,
                           LocalDateTime submittedAt, LocalDateTime holdDeadline,
                           LocalDateTime decidedAt, String preview,
                           long authorPriorRejections) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldView(String fieldName, String text, String verdict, String topLabel,
                            Double topScore, Map<String, Object> scores, String blocklistHit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseDetail(QueueRow summary, List<FieldView> fields, Map<String, Object> thresholds) {
    }

    public record DecisionRequest(@NotBlank String action,
                                  @Size(max = 500) String reason,
                                  /* When true, the reviewed text is also added to
                                     training_examples with labels derived from the
                                     decision — §12.3's highest-leverage source. */
                                  Boolean teachModel) {
    }

    public record BulkDecisionRequest(@NotBlank String action,
                                      @NotNull @Size(min = 1, max = 100) List<UUID> caseIds,
                                      @Size(max = 500) String reason,
                                      Boolean teachModel) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkResult(UUID caseId, String outcome, String error) {
    }

    // ── the queue ───────────────────────────────────────────────────────

    /**
     * Paginated review queue. Defaults to riskiest-first ({@code maxScore} desc)
     * because a moderator's time is best spent where the model was least sure it
     * was safe; {@code sort=oldest} switches to FIFO when the queue is being
     * drained rather than triaged.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> queue(
            @RequestParam(defaultValue = "IN_REVIEW") String status,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(defaultValue = "risk") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        ModerationStatus target = parseStatus(status);
        Sort ordering = "oldest".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Direction.ASC, "submittedAt")
                : Sort.by(Sort.Direction.DESC, "maxScore");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Pages.clamp(pageSize), ordering);

        Page<ModerationCase> cases;
        if (Boolean.TRUE.equals(slaBreached)) {
            cases = caseRepository.findByStatusAndSlaBreached(target, true, pageable);
        } else if (entityType != null && !entityType.isBlank()) {
            ModeratedEntityType type = ModeratedEntityType.parse(entityType)
                    .orElseThrow(() -> new BadRequestException(
                            ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(entityType),
                            ModerationMessages.INVALID_MODERATION_SETTING));
            cases = caseRepository.findByStatusAndEntityType(target, type, pageable);
        } else {
            cases = caseRepository.findByStatus(target, pageable);
        }

        Map<UUID, String> previews = previewsFor(cases.getContent());
        // One count query per DISTINCT author, not per row. A 50-row page from a
        // handful of repeat offenders was 50 queries; with open-in-view off they
        // are 50 separate connection checkouts.
        Map<UUID, Long> priorRejections = priorRejectionsFor(cases.getContent());
        List<QueueRow> rows = cases.getContent().stream()
                .map(row -> toRow(row, previews.get(row.getId()),
                        priorRejections.getOrDefault(row.getAuthorId(), 0L)))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("page", cases.getNumber());
        body.put("pageSize", cases.getSize());
        body.put("totalElements", cases.getTotalElements());
        body.put("totalPages", cases.getTotalPages());
        body.put("counts", metricsService.queueCounts());
        return ResponseEntity.ok(body);
    }

    /** Full review screen: every field, its raw scores, and the bands in force. */
    @GetMapping("/{caseId}")
    public ResponseEntity<CaseDetail> detail(@PathVariable UUID caseId) {
        ModerationCase moderationCase = caseRepository.findWithFields(caseId)
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.MODERATION_CASE_NOT_FOUND_MSG.formatted(caseId),
                        ModerationMessages.MODERATION_CASE_NOT_FOUND));

        List<FieldView> fields = moderationCase.getFields().stream()
                .map(field -> toFieldView(field, moderationCase.getEntityType()))
                .toList();
        return ResponseEntity.ok(new CaseDetail(
                toRow(moderationCase, preview(moderationCase)),
                fields,
                thresholdView(moderationCase.getEntityType())));
    }

    // ── decisions ───────────────────────────────────────────────────────

    /**
     * Approve or reject one case.
     *
     * <p>No step-up: a moderator works this queue continuously and re-auth on
     * every item would make the tool unusable. The bulk endpoint, which can move
     * a hundred pieces of content at once, does require it.</p>
     */
    @PostMapping("/{caseId}/decide")
    public ResponseEntity<QueueRow> decide(@PathVariable UUID caseId,
                                           @jakarta.validation.Valid @RequestBody DecisionRequest request) {
        boolean approve = parseAction(request.action());
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);

        ModerationCase decided = moderationService.decideManually(caseId, approve,
                request.reason(), adminId);

        if (Boolean.TRUE.equals(request.teachModel())) {
            teach(decided, approve);
        }

        adminAuditor.record(AuditOperation.UPDATE, "ModerationCase", caseId,
                approve ? "ADMIN_MODERATION_APPROVE" : "ADMIN_MODERATION_REJECT",
                "%s %s — %s".formatted(decided.getEntityType(), decided.getEntityRef(),
                        request.reason() == null ? "no reason given" : request.reason()));

        return ResponseEntity.ok(toRow(decided, preview(decided)));
    }

    /**
     * Bulk approve/reject — §12.1's "approve all with max score &lt; 0.4" case.
     * Step-up gated: this is the one endpoint that can publish or bury a hundred
     * pieces of content in a single call.
     */
    @PostMapping("/bulk")
    @ak.dev.irc.app.admin.support.RequiresStepUp
    public ResponseEntity<List<BulkResult>> bulk(@jakarta.validation.Valid @RequestBody BulkDecisionRequest request) {
        boolean approve = parseAction(request.action());
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);

        List<BulkResult> results = new ArrayList<>(request.caseIds().size());
        for (UUID caseId : request.caseIds()) {
            try {
                ModerationCase decided = moderationService.decideManually(caseId, approve,
                        request.reason(), adminId);
                if (Boolean.TRUE.equals(request.teachModel())) {
                    teach(decided, approve);
                }
                results.add(new BulkResult(caseId, "ok", null));
            } catch (Exception ex) {
                results.add(new BulkResult(caseId, "error", ex.getMessage()));
            }
        }

        long ok = results.stream().filter(r -> "ok".equals(r.outcome())).count();
        adminAuditor.record(AuditOperation.UPDATE, "ModerationCase", null,
                "ADMIN_MODERATION_REVIEW_BULK",
                "%s on %d cases, ok=%d".formatted(request.action(), request.caseIds().size(), ok));
        return ResponseEntity.ok(results);
    }

    /** Re-runs the classifier on a case — useful right after a model promotion. */
    @PostMapping("/{caseId}/rescore")
    public ResponseEntity<Map<String, Object>> rescore(@PathVariable UUID caseId) {
        ModerationStatus status = moderationService.rescore(caseId);
        return ResponseEntity.ok(Map.of("caseId", caseId,
                "status", status == null ? "GONE" : status.name()));
    }

    // ── metrics (§12.5) ─────────────────────────────────────────────────

    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR','ANALYST')")   // §6 matrix: observability is ANALYST RO
    public ResponseEntity<Map<String, Object>> metrics(
            @RequestParam(defaultValue = "24") int windowHours) {
        return ResponseEntity.ok(metricsService.overview(windowHours));
    }

    // ── internals ───────────────────────────────────────────────────────

    /**
     * Feeds the decision back into the dataset (§12.3, §13).
     *
     * <p>Labels are derived from the decision itself: a rejection means the text
     * really was toxic, an approval means it was not. That is a coarse signal —
     * it cannot say <em>which</em> of the six labels applied — so a rejection is
     * recorded as {@code toxic} only, and a moderator who knows better can refine
     * the row in the dataset browser. Recording a confident guess across all six
     * labels would poison the training set faster than it helped.</p>
     */
    private void teach(ModerationCase moderationCase, boolean approved) {
        if (PRIVATE_TEXT.contains(moderationCase.getEntityType())) {
            // Private correspondence never enters the training set. It would leave
            // the platform entirely — shipped to the training container and baked
            // into model weights — which is a far larger disclosure than showing it
            // on a screen, and staff cannot even read it to judge whether it is a
            // good example.
            return;
        }
        List<ModerationCaseField> fields = moderationCase.getFields().isEmpty()
                ? fieldRepository.findByCaseId(moderationCase.getId())
                : moderationCase.getFields();

        Map<String, Object> labels = approved
                ? Map.of()
                : Map.of("toxic", 1);
        // An approval that the model wanted to block, or a rejection it wanted to
        // allow, is a correction; anything else merely confirms what it already
        // thought and is worth far less as training signal.
        boolean modelWasWrong = approved
                ? moderationCase.getMaxScore() != null && moderationCase.getMaxScore() > 0.3
                : moderationCase.getMaxScore() != null && moderationCase.getMaxScore() < 0.3;
        TrainingExampleSource source = modelWasWrong
                ? TrainingExampleSource.ADMIN_CORRECTION
                : TrainingExampleSource.REVIEW_PROMOTION;

        for (ModerationCaseField field : fields) {
            if (field.getText() == null || field.getText().isBlank()) continue;
            trainingService.addExample(field.getText(), labels, source, moderationCase.getId(),
                    "from review of " + moderationCase.getEntityType());
        }
    }

    /** Distinct-author count lookup for one page — see the call site. */
    private Map<UUID, Long> priorRejectionsFor(List<ModerationCase> cases) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (ModerationCase row : cases) {
            UUID authorId = row.getAuthorId();
            if (authorId == null || counts.containsKey(authorId)) continue;
            counts.put(authorId,
                    caseRepository.countByAuthorIdAndStatus(authorId, ModerationStatus.REJECTED));
        }
        return counts;
    }

    private QueueRow toRow(ModerationCase row, String preview, long authorPriorRejections) {
        return new QueueRow(
                row.getId(),
                row.getEntityType().name(),
                ModerationNotifier.label(row.getEntityType()),
                row.getEntityRef(),
                row.getParentRef(),
                row.getAuthorId(),
                row.getStatus().name(),
                row.getReasonCode(),
                row.getMaxLabel(),
                row.getMaxScore(),
                row.getBlocklistHit(),
                row.isSlaBreached(),
                row.getModelVersion(),
                row.getSubmittedAt(),
                row.getHoldDeadline(),
                row.getDecidedAt(),
                preview,
                authorPriorRejections);
    }

    /** Single-row convenience: the detail and decide screens render one case. */
    private QueueRow toRow(ModerationCase row, String preview) {
        long prior = row.getAuthorId() == null ? 0
                : caseRepository.countByAuthorIdAndStatus(row.getAuthorId(), ModerationStatus.REJECTED);
        return toRow(row, preview, prior);
    }

    /**
     * Private correspondence is never rendered to staff — chat and DM bodies,
     * live-stream chat lines. That is an absolute boundary in this codebase
     * (docs/admin/architecture.md), and a moderation queue is not an exception
     * to it: the whole point of scoring these automatically is that nobody has
     * to read them.
     *
     * <p>The case still carries per-label scores, the field name and the
     * threshold band, so a moderator can act on a held message without ever
     * seeing what it says. In practice these types auto-decide — they only reach
     * a human when the model was unsure or unavailable.</p>
     */
    private static final java.util.Set<ModeratedEntityType> PRIVATE_TEXT =
            java.util.EnumSet.of(ModeratedEntityType.CHAT_MESSAGE, ModeratedEntityType.LIVE_CHAT);

    private static final String REDACTED =
            "[private message — body withheld from staff by policy]";

    private static FieldView toFieldView(ModerationCaseField field, ModeratedEntityType type) {
        Map<String, Object> scores = new LinkedHashMap<>();
        if (field.getScoresJson() != null) {
            try {
                scores = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(field.getScoresJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<>() { });
            } catch (Exception ignored) {
                // A malformed score blob must not blank the whole review screen —
                // the moderator still needs to read the text.
            }
        }
        String text = PRIVATE_TEXT.contains(type) ? REDACTED : field.getText();
        return new FieldView(field.getFieldName(), text,
                field.getVerdict() == null ? null : field.getVerdict().name(),
                field.getMaxLabel(), field.getMaxScore(), scores, field.getBlocklistHit());
    }

    /**
     * One bulk field load for the whole page. Rendering a queue of 50 rows with a
     * per-row field query is the classic N+1 this codebase has already been
     * burned by on the user-profile join.
     */
    private Map<UUID, String> previewsFor(List<ModerationCase> cases) {
        if (cases.isEmpty()) return Map.of();
        List<UUID> ids = cases.stream().map(ModerationCase::getId).toList();
        Map<ModeratedEntityType, Boolean> privateByCase = new LinkedHashMap<>();
        Map<UUID, ModeratedEntityType> typeByCase = new LinkedHashMap<>();
        for (ModerationCase row : cases) {
            typeByCase.put(row.getId(), row.getEntityType());
            privateByCase.put(row.getEntityType(), PRIVATE_TEXT.contains(row.getEntityType()));
        }

        Map<UUID, String> previews = new LinkedHashMap<>();
        for (ModerationCaseField field : fieldRepository.findByCaseIds(ids)) {
            UUID caseId = field.getModerationCase().getId();
            previews.computeIfAbsent(caseId, key ->
                    Boolean.TRUE.equals(privateByCase.get(typeByCase.get(key)))
                            ? REDACTED
                            : truncate(field.getText()));
        }
        return previews;
    }

    private static String preview(ModerationCase moderationCase) {
        if (PRIVATE_TEXT.contains(moderationCase.getEntityType())) return REDACTED;
        List<ModerationCaseField> fields = moderationCase.getFields();
        return fields == null || fields.isEmpty() ? null : truncate(fields.get(0).getText());
    }

    /**
     * The bands actually in force for this case's entity type, so the reviewer
     * can see why it landed in the uncertain middle instead of being decided
     * automatically — §12.1 asks for the threshold band to be visualised next to
     * the scores, and that is impossible without shipping the cut points.
     */
    private Map<String, Object> thresholdView(ModeratedEntityType type) {
        var thresholds = settings.thresholds(type);
        Map<String, Object> bands = new LinkedHashMap<>();
        for (var label : ak.dev.irc.app.moderation.enums.ModerationLabel.values()) {
            var band = thresholds.band(label);
            bands.put(label.wire(), Map.of("low", band.low(), "high", band.high()));
        }
        return Map.of("entityType", type.key(),
                "bands", bands,
                "holdMs", settings.holdCeiling(type).toMillis(),
                "fallback", settings.fallback(type).name());
    }

    private static ModerationStatus parseStatus(String raw) {
        try {
            return ModerationStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BadRequestException(
                    ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(raw),
                    ModerationMessages.INVALID_MODERATION_SETTING);
        }
    }

    private static boolean parseAction(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if ("APPROVE".equals(normalized)) return true;
        if ("REJECT".equals(normalized)) return false;
        throw new BadRequestException(ModerationMessages.INVALID_MODERATION_ACTION_MSG,
                ModerationMessages.INVALID_MODERATION_ACTION);
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
