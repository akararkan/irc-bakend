package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.client.InferenceUnavailableException;
import ak.dev.irc.app.moderation.client.ModerationInferenceClient;
import ak.dev.irc.app.moderation.entity.ModerationGoldenCase;
import ak.dev.irc.app.moderation.entity.ModerationModelVersion;
import ak.dev.irc.app.moderation.entity.ModerationTrainingExample;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
import ak.dev.irc.app.moderation.repository.ModerationGoldenCaseRepository;
import ak.dev.irc.app.moderation.repository.ModerationModelVersionRepository;
import ak.dev.irc.app.moderation.repository.ModerationTrainingExampleRepository;
import ak.dev.irc.app.moderation.service.ModerationMetricsService;
import ak.dev.irc.app.moderation.service.ModerationTrainingService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Training-data manager and model registry (MODERATION_ROADMAP.md §12.3, §12.4)
 * — the "teach it new words and sentences" surface, plus retrain / promote /
 * rollback.
 *
 * <p>ADMIN-only by default, matching §12.6: a Moderator may work the queue and
 * the blocklist but may not decide which model scores the platform. The single
 * exception is the training-example write, which Moderators can use because
 * every correction they make in the queue is exactly the signal §13 wants.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/moderation/model")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")   // §12.6 matrix: promote/rollback is ML/Platform Admin only
public class AdminModerationModelController {

    private final ModerationTrainingService trainingService;
    private final ModerationTrainingImportService importService;
    private final ModerationMetricsService metricsService;
    private final ModerationModelVersionRepository versionRepository;
    private final ModerationTrainingExampleRepository exampleRepository;
    private final ModerationGoldenCaseRepository goldenRepository;
    private final ModerationInferenceClient inferenceClient;
    private final ModerationProperties properties;
    private final AdminAuditor adminAuditor;

    // ── DTOs ────────────────────────────────────────────────────────────

    public record ExampleRequest(@NotBlank @Size(max = 5000) String text,
                                 Map<String, Object> labels,
                                 @Size(max = 300) String note) {
    }

    public record WordRequest(@NotBlank @Size(max = 100) String word,
                              Map<String, Object> labels,
                              @Size(max = 300) String note) {
    }

    public record RetrainRequest(@Size(max = 40) String baseVersion,
                                 @Size(max = 500) String notes) {
    }

    public record PromoteRequest(Boolean force) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionView(UUID id, String version, String status, String jobId,
                              String baseCheckpoint, int trainingExamples, int validationCount,
                              Double macroF1, Boolean gatePassed, String gateDetail,
                              String artifactPath, String notes, String error,
                              LocalDateTime trainedAt, LocalDateTime completedAt,
                              LocalDateTime promotedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExampleView(UUID id, String text, Map<String, Integer> labels, String source,
                              String note, String trainedInVersion, LocalDateTime addedAt) {
    }

    public record ScoreProbeRequest(@NotBlank @Size(max = 5000) String text) {
    }

    // ── training data (§12.3) ───────────────────────────────────────────

    @GetMapping("/training-examples")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<Map<String, Object>> listExamples(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        PageRequest pageable = PageRequest.of(Math.max(0, page), Pages.clamp(pageSize));
        Page<ModerationTrainingExample> rows = (source == null || source.isBlank())
                ? exampleRepository.findAllByOrderByAddedAtDesc(pageable)
                : exampleRepository.findBySourceOrderByAddedAtDesc(parseSource(source), pageable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.getContent().stream().map(AdminModerationModelController::toView).toList());
        body.put("page", rows.getNumber());
        body.put("pageSize", rows.getSize());
        body.put("totalElements", rows.getTotalElements());
        body.put("summary", trainingService.datasetSummary());
        return ResponseEntity.ok(body);
    }

    /** Add one labeled sentence. Re-posting the same text updates its labels. */
    @PostMapping("/training-examples")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<ExampleView> addExample(@jakarta.validation.Valid @RequestBody ExampleRequest request) {
        ModerationTrainingExample saved = trainingService.addExample(request.text(),
                request.labels(), TrainingExampleSource.ADMIN_MANUAL, null, request.note());
        adminAuditor.record(AuditOperation.CREATE, "ModerationTrainingExample", saved.getId(),
                "ADMIN_MODERATION_EXAMPLE_ADD", truncate(request.text()));
        return ResponseEntity.status(201).body(toView(saved));
    }

    /**
     * Add a single word (§12.3). Expanded into template sentences server-side —
     * a bare token teaches a sentence-level classifier almost nothing, and the
     * response returns every row created so the admin can see what was actually
     * stored rather than guessing.
     */
    @PostMapping("/training-examples/word")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<Map<String, Object>> addWord(@jakarta.validation.Valid @RequestBody WordRequest request) {
        List<ModerationTrainingExample> created =
                trainingService.addWord(request.word(), request.labels(), request.note());
        adminAuditor.record(AuditOperation.CREATE, "ModerationTrainingExample", null,
                "ADMIN_MODERATION_WORD_ADD", request.word());
        return ResponseEntity.status(201).body(Map.of(
                "word", request.word(),
                "created", created.stream().map(AdminModerationModelController::toView).toList(),
                "note", "The word was expanded into template sentences. "
                        + "For an instant, no-retrain ban add it to the blocklist as well."));
    }

    /**
     * CSV bulk import — the file-based sibling of the two forms above. An admin
     * curates sentences or a word list in a spreadsheet, exports CSV UTF-8, and
     * uploads it here; {@code kind=words} rows flagged {@code blocklist=yes} are
     * also pushed to the platform blocklist in the same pass. Step-up gated —
     * one call can move thousands of dataset rows and touch the blocklist.
     */
    @PostMapping(value = "/training-examples/import",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    @RequiresStepUp
    public ResponseEntity<ModerationTrainingImportService.ImportReport> importExamples(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("kind") String kind,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean allowPartial) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException unreadable) {
            throw new BadRequestException(
                    ModerationMessages.INVALID_IMPORT_FILE_MSG.formatted("the upload could not be read"),
                    ModerationMessages.INVALID_IMPORT_FILE);
        }
        ModerationTrainingImportService.ImportReport report =
                importService.importFile(bytes, kind, dryRun, allowPartial);
        if (!report.dryRun()) {
            adminAuditor.record(AuditOperation.CREATE, "ModerationTrainingExample", null,
                    "ADMIN_MODERATION_IMPORT",
                    "kind=%s file=%s rows=%d applied=%d updated=%d blocklist=%d errors=%d".formatted(
                            report.kind(), truncate(file.getOriginalFilename()), report.totalRows(),
                            report.applied(), report.updated(), report.blocklistAdded(),
                            report.errors().size()));
        }
        return ResponseEntity.ok(report);
    }

    @DeleteMapping("/training-examples/{id}")
    public ResponseEntity<Void> deleteExample(@PathVariable UUID id) {
        exampleRepository.deleteById(id);
        adminAuditor.record(AuditOperation.DELETE, "ModerationTrainingExample", id,
                "ADMIN_MODERATION_EXAMPLE_REMOVE", null);
        return ResponseEntity.noContent().build();
    }

    // ── golden regression set (§17) ─────────────────────────────────────

    @GetMapping("/golden-cases")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<List<ModerationGoldenCase>> goldenCases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok(goldenRepository
                .findAllByOrderByAddedAtDesc(PageRequest.of(Math.max(0, page), Pages.clamp(pageSize)))
                .getContent());
    }

    @PostMapping("/golden-cases")
    public ResponseEntity<ModerationGoldenCase> addGoldenCase(@jakarta.validation.Valid @RequestBody ExampleRequest request) {
        ModerationGoldenCase saved = trainingService.addGoldenCase(request.text(),
                request.labels(), request.note());
        adminAuditor.record(AuditOperation.CREATE, "ModerationGoldenCase", saved.getId(),
                "ADMIN_MODERATION_GOLDEN_ADD", truncate(request.text()));
        return ResponseEntity.status(201).body(saved);
    }

    @DeleteMapping("/golden-cases/{id}")
    public ResponseEntity<Void> deleteGoldenCase(@PathVariable UUID id) {
        goldenRepository.deleteById(id);
        adminAuditor.record(AuditOperation.DELETE, "ModerationGoldenCase", id,
                "ADMIN_MODERATION_GOLDEN_REMOVE", null);
        return ResponseEntity.noContent().build();
    }

    // ── model registry (§12.4) ──────────────────────────────────────────

    @GetMapping("/versions")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> versions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<ModerationModelVersion> rows = versionRepository.findAllByOrderByTrainedAtDesc(
                PageRequest.of(Math.max(0, page), Pages.clamp(pageSize)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.getContent().stream()
                .map(AdminModerationModelController::toView).toList());
        body.put("totalElements", rows.getTotalElements());
        body.put("health", metricsService.modelHealth());
        return ResponseEntity.ok(body);
    }

    /**
     * Kicks off a fine-tune (§12.4). Step-up gated — it consumes real compute and
     * produces an artifact somebody will later be asked to trust.
     */
    @PostMapping("/retrain")
    @RequiresStepUp
    public ResponseEntity<VersionView> retrain(@jakarta.validation.Valid @RequestBody RetrainRequest request) {
        ModerationModelVersion started =
                trainingService.startRetrain(request.baseVersion(), request.notes());
        adminAuditor.record(AuditOperation.CREATE, "ModerationModelVersion", started.getId(),
                "ADMIN_MODERATION_RETRAIN",
                "job=%s base=%s".formatted(started.getJobId(), started.getBaseCheckpoint()));
        return ResponseEntity.accepted().body(toView(started));
    }

    /** Forces a status refresh for in-flight jobs instead of waiting for the poller. */
    @PostMapping("/retrain/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        return ResponseEntity.ok(Map.of("settled", trainingService.pollRunningJobs()));
    }

    /**
     * Completion webhook from the training container. Deliberately outside the
     * {@code @PreAuthorize} class rule — the caller is a container, not a staff
     * member — and authenticated by the shared token instead.
     */
    @PostMapping("/train-callback")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> trainCallback(
            @RequestHeader(value = "X-Training-Token", required = false) String token,
            @RequestBody JsonNode payload) {
        String expected = properties.getTraining().getCallbackToken();
        if (!expected.isBlank() && !expected.equals(token)) {
            throw new UnauthorizedException(ModerationMessages.INVALID_CALLBACK_TOKEN_MSG,
                    ModerationMessages.INVALID_CALLBACK_TOKEN);
        }
        trainingService.ingestJobResult(payload);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/versions/{id}/promote")
    @RequiresStepUp
    public ResponseEntity<VersionView> promote(@PathVariable UUID id,
                                               @RequestBody(required = false) PromoteRequest request) {
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        ModerationModelVersion promoted = trainingService.promote(id, force);
        adminAuditor.record(AuditOperation.UPDATE, "ModerationModelVersion", id,
                "ADMIN_MODERATION_MODEL_PROMOTE",
                promoted.getVersion() + (force ? " (gate overridden)" : ""));
        return ResponseEntity.ok(toView(promoted));
    }

    @PostMapping("/versions/{id}/shadow")
    public ResponseEntity<VersionView> shadow(@PathVariable UUID id) {
        ModerationModelVersion version = trainingService.markShadow(id);
        adminAuditor.record(AuditOperation.UPDATE, "ModerationModelVersion", id,
                "ADMIN_MODERATION_MODEL_SHADOW", version.getVersion());
        return ResponseEntity.ok(toView(version));
    }

    /** Re-points at the most recently retired version — the §12.4 safety valve. */
    @PostMapping("/rollback")
    @RequiresStepUp
    public ResponseEntity<VersionView> rollback() {
        ModerationModelVersion restored = trainingService.rollback();
        adminAuditor.record(AuditOperation.UPDATE, "ModerationModelVersion", restored.getId(),
                "ADMIN_MODERATION_MODEL_ROLLBACK", restored.getVersion());
        return ResponseEntity.ok(toView(restored));
    }

    /**
     * Scores an arbitrary string against the live model. The fastest way to
     * answer "why did this get through?" without digging through cases, and the
     * only place raw scores are exposed for text that was never submitted.
     */
    @PostMapping("/score-probe")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<Map<String, Object>> scoreProbe(@jakarta.validation.Valid @RequestBody ScoreProbeRequest request) {
        try {
            var result = inferenceClient.score(request.text(), java.time.Duration.ofSeconds(5));
            Map<String, Object> scores = new LinkedHashMap<>();
            result.scores().forEach((label, value) -> scores.put(label.wire(), value));
            return ResponseEntity.ok(Map.of(
                    "modelVersion", result.modelVersion(),
                    "inferenceMs", result.inferenceMs(),
                    "scores", scores));
        } catch (InferenceUnavailableException down) {
            throw new BadRequestException(ModerationMessages.INFERENCE_UNAVAILABLE_MSG,
                    ModerationMessages.INFERENCE_UNAVAILABLE);
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private static VersionView toView(ModerationModelVersion version) {
        return new VersionView(version.getId(), version.getVersion(), version.getStatus().name(),
                version.getJobId(), version.getBaseCheckpoint(), version.getTrainingExamplesCount(),
                version.getValidationCount(), version.getMacroF1(), version.getGatePassed(),
                version.getGateDetail(), version.getArtifactPath(), version.getNotes(),
                version.getError(), version.getTrainedAt(), version.getCompletedAt(),
                version.getPromotedAt());
    }

    private static ExampleView toView(ModerationTrainingExample example) {
        return new ExampleView(example.getId(), example.getText(), example.labelMap(),
                example.getSource() == null ? null : example.getSource().name(),
                example.getNote(), example.getTrainedInVersion(), example.getAddedAt());
    }

    private static TrainingExampleSource parseSource(String raw) {
        try {
            return TrainingExampleSource.valueOf(raw.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BadRequestException(
                    ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(raw),
                    ModerationMessages.INVALID_MODERATION_SETTING);
        }
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() <= 120 ? text : text.substring(0, 120);
    }
}
