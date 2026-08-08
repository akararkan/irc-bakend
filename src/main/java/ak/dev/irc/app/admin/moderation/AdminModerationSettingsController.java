package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.moderation.engine.FieldDecision;
import ak.dev.irc.app.moderation.engine.ModerationDecisionEngine;
import ak.dev.irc.app.moderation.engine.ModerationSettingsService;
import ak.dev.irc.app.moderation.engine.ModerationThresholds;
import ak.dev.irc.app.moderation.enums.FallbackPolicy;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.repository.ModerationCaseFieldRepository;
import ak.dev.irc.app.moderation.service.ModerationMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime tuning for the Decision Engine (MODERATION_ROADMAP.md §8.1, §10.4).
 *
 * <p>This is the endpoint that makes the whole architecture worth its split: the
 * model container knows nothing about thresholds, so changing how strict the
 * platform is means one row in {@code moderation_settings} — no redeploy of
 * either service, no retrain.</p>
 *
 * <p>Content Admins can retune thresholds (§12.6 matrix); the master kill switch
 * and per-entity disablement stay ADMIN-only, because turning moderation off for
 * a whole entity type is a different order of decision from nudging a cut point.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/moderation/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §12.6: Content Admin may edit thresholds
public class AdminModerationSettingsController {

    private final ModerationSettingsService settings;
    private final ModerationDecisionEngine decisionEngine;
    private final ModerationMetricsService metricsService;
    private final ModerationCaseFieldRepository fieldRepository;
    private final ObjectMapper objectMapper;
    private final AdminAuditor adminAuditor;

    // ── DTOs ────────────────────────────────────────────────────────────

    public record BandPatch(Double low, Double high) {
    }

    /**
     * Partial threshold update. {@code entityType} null means the global default
     * for those labels; a value scopes the override to that type only, which is
     * how §8.1's "research tolerates sharper critique than public comments" is
     * expressed without loosening threats anywhere.
     */
    public record ThresholdPatch(String entityType, Map<String, BandPatch> labels) {
    }

    public record HoldPatch(String entityType, Long holdMs, Long inlineMs, String fallback,
                            Boolean enabled) {
    }

    public record SettingPatch(@NotBlank @Size(max = 120) String key,
                               @NotBlank @Size(max = 200) String value) {
    }

    public record DryRunRequest(String entityType, Map<String, BandPatch> labels,
                                @Size(max = 500) List<UUID> caseIds) {
    }

    // ── read ────────────────────────────────────────────────────────────

    /**
     * Stored overrides plus the fully resolved effective view. Both matter: the
     * overrides say what an admin changed, the effective view says what is
     * actually being enforced right now.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> current() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overrides", settings.overrides());
        body.put("effective", settings.effective());
        body.put("model", metricsService.modelHealth());
        if (!settings.globallyEnabled()) {
            body.put("warning", ModerationMessages.WARN_MODERATION_DISABLED);
        } else if (!Boolean.TRUE.equals(((Map<?, ?>) body.get("model")).get("inferenceUp"))) {
            body.put("warning", ModerationMessages.WARN_INFERENCE_DOWN);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> thresholds(
            @RequestParam(required = false) String entityType) {
        ModeratedEntityType type = entityType == null || entityType.isBlank()
                ? ModeratedEntityType.POST
                : parseType(entityType);
        ModerationThresholds resolved = settings.thresholds(type);
        Map<String, Object> bands = new LinkedHashMap<>();
        for (ModerationLabel label : ModerationLabel.values()) {
            ModerationThresholds.Band band = resolved.band(label);
            bands.put(label.wire(), Map.of("low", band.low(), "high", band.high()));
        }
        return ResponseEntity.ok(Map.of("entityType", type.key(), "bands", bands));
    }

    // ── write ───────────────────────────────────────────────────────────

    @PutMapping("/thresholds")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> putThresholds(@jakarta.validation.Valid @RequestBody ThresholdPatch patch) {
        if (patch.labels() == null || patch.labels().isEmpty()) {
            throw new BadRequestException(ModerationMessages.INVALID_THRESHOLD_MSG,
                    ModerationMessages.INVALID_THRESHOLD);
        }
        String scope = patch.entityType() == null || patch.entityType().isBlank()
                ? null : parseType(patch.entityType()).key();

        Map<String, String> writes = new LinkedHashMap<>();
        List<String> summary = new ArrayList<>();
        for (Map.Entry<String, BandPatch> entry : patch.labels().entrySet()) {
            ModerationLabel label = ModerationLabel.fromWire(entry.getKey())
                    .orElseThrow(() -> new BadRequestException(
                            ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(entry.getKey()),
                            ModerationMessages.INVALID_MODERATION_SETTING));
            BandPatch band = entry.getValue();
            validate(band);

            String prefix = "threshold." + (scope == null ? "" : scope + ".") + label.wire();
            if (band.low() != null) writes.put(prefix + ".low", String.valueOf(band.low()));
            if (band.high() != null) writes.put(prefix + ".high", String.valueOf(band.high()));
            summary.add("%s=%s/%s".formatted(label.wire(), band.low(), band.high()));
        }

        settings.putAll(writes);
        adminAuditor.record(AuditOperation.UPDATE, "ModerationSetting", null,
                "ADMIN_MODERATION_THRESHOLDS",
                (scope == null ? "global" : scope) + ": " + String.join(", ", summary));
        return ResponseEntity.ok(Map.of("applied", writes, "effective", settings.effective()));
    }

    /** Hold ceilings, inline budgets, fallback policy and per-type enablement (§5.3, §5.6). */
    @PutMapping("/hold-durations")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> putHold(@jakarta.validation.Valid @RequestBody HoldPatch patch) {
        ModeratedEntityType type = parseType(patch.entityType());
        Map<String, String> writes = new LinkedHashMap<>();

        if (patch.holdMs() != null) {
            if (patch.holdMs() < 500 || patch.holdMs() > 600_000) {
                throw new BadRequestException(
                        "Hold ceiling must be between 500ms and 600000ms.",
                        ModerationMessages.INVALID_MODERATION_SETTING);
            }
            writes.put("hold." + type.key() + ".ms", String.valueOf(patch.holdMs()));
        }
        if (patch.inlineMs() != null) {
            writes.put("inline." + type.key() + ".ms", String.valueOf(patch.inlineMs()));
        }
        if (patch.fallback() != null) {
            try {
                FallbackPolicy.valueOf(patch.fallback().trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException bad) {
                throw new BadRequestException(
                        ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(patch.fallback()),
                        ModerationMessages.INVALID_MODERATION_SETTING);
            }
            writes.put("fallback." + type.key(), patch.fallback().trim().toUpperCase());
        }
        if (patch.enabled() != null) {
            writes.put("enabled." + type.key(), String.valueOf(patch.enabled()));
        }
        if (writes.isEmpty()) {
            throw new BadRequestException(ModerationMessages.INVALID_MODERATION_SETTING_MSG
                    .formatted("(empty patch)"), ModerationMessages.INVALID_MODERATION_SETTING);
        }

        settings.putAll(writes);
        adminAuditor.record(AuditOperation.UPDATE, "ModerationSetting", null,
                "ADMIN_MODERATION_HOLD_CONFIG", type.key() + ": " + writes);
        return ResponseEntity.ok(Map.of("applied", writes, "effective", settings.effective()));
    }

    /** Escape hatch for any key in the namespace, including the master switch. */
    @PutMapping("/raw")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")   // §12.6: the kill switch is not a Moderator lever
    public ResponseEntity<Map<String, Object>> putRaw(@jakarta.validation.Valid @RequestBody SettingPatch patch) {
        settings.put(patch.key(), patch.value());
        adminAuditor.record(AuditOperation.UPDATE, "ModerationSetting", null,
                "ADMIN_MODERATION_SETTING_SET", patch.key() + "=" + patch.value());
        return ResponseEntity.ok(Map.of("overrides", settings.overrides()));
    }

    @DeleteMapping("/raw/{key}")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeRaw(@PathVariable String key) {
        settings.remove(key);
        adminAuditor.record(AuditOperation.DELETE, "ModerationSetting", null,
                "ADMIN_MODERATION_SETTING_CLEAR", key);
        return ResponseEntity.noContent().build();
    }

    /** Drops every override, returning to the yaml bootstrap policy. */
    @PostMapping("/reset")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reset() {
        settings.resetAll();
        adminAuditor.record(AuditOperation.DELETE, "ModerationSetting", null,
                "ADMIN_MODERATION_SETTINGS_RESET", "all overrides cleared");
        return ResponseEntity.ok(Map.of("effective", settings.effective()));
    }

    // ── dry run ─────────────────────────────────────────────────────────

    /**
     * Replays proposed thresholds against already-stored scores and reports what
     * would change. Nothing is written and no model call is made — the raw score
     * vectors are already on {@code moderation_case_fields}, which is exactly why
     * §3.3 insists on storing them.
     *
     * <p>Without this, tuning a threshold is a guess whose blast radius you only
     * discover in production.</p>
     */
    @PostMapping("/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(@jakarta.validation.Valid @RequestBody DryRunRequest request) {
        ModeratedEntityType type = request.entityType() == null || request.entityType().isBlank()
                ? ModeratedEntityType.POST
                : parseType(request.entityType());

        ModerationThresholds current = settings.thresholds(type);
        Map<ModerationLabel, ModerationThresholds.Band> proposedBands =
                new EnumMap<>(ModerationLabel.class);
        for (ModerationLabel label : ModerationLabel.values()) {
            ModerationThresholds.Band base = current.band(label);
            BandPatch patch = request.labels() == null ? null : request.labels().get(label.wire());
            if (patch == null) {
                proposedBands.put(label, base);
            } else {
                validate(patch);
                proposedBands.put(label, new ModerationThresholds.Band(
                        patch.low() == null ? base.low() : patch.low(),
                        patch.high() == null ? base.high() : patch.high()));
            }
        }
        ModerationThresholds proposed = ModerationThresholds.of(proposedBands);

        List<Map<String, Object>> changes = new ArrayList<>();
        int unchanged = 0;
        var fields = request.caseIds() == null || request.caseIds().isEmpty()
                ? List.<ak.dev.irc.app.moderation.entity.ModerationCaseField>of()
                : fieldRepository.findByCaseIds(request.caseIds());

        for (var field : fields) {
            Map<ModerationLabel, Double> scores = readScores(field.getScoresJson());
            if (scores.isEmpty()) continue;
            FieldDecision before = decisionEngine.decide(scores, current);
            FieldDecision after = decisionEngine.decide(scores, proposed);
            if (before.verdict() == after.verdict()) {
                unchanged++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", field.getModerationCase().getId());
            row.put("field", field.getFieldName());
            row.put("before", before.verdict().name());
            row.put("after", after.verdict().name());
            row.put("topLabel", after.topLabel() == null ? null : after.topLabel().wire());
            row.put("topScore", after.topScore());
            changes.add(row);
        }

        return ResponseEntity.ok(Map.of(
                "entityType", type.key(),
                "evaluated", fields.size(),
                "unchanged", unchanged,
                "changed", changes));
    }

    // ── internals ───────────────────────────────────────────────────────

    private Map<ModerationLabel, Double> readScores(String json) {
        Map<ModerationLabel, Double> scores = new EnumMap<>(ModerationLabel.class);
        if (json == null || json.isBlank()) return scores;
        try {
            Map<String, Double> raw = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            raw.forEach((key, value) ->
                    ModerationLabel.fromWire(key).ifPresent(label -> scores.put(label, value)));
        } catch (Exception ex) {
            log.debug("[MODERATION] unreadable score blob skipped in dry-run: {}", ex.getMessage());
        }
        return scores;
    }

    private static void validate(BandPatch band) {
        if (band == null) {
            throw new BadRequestException(ModerationMessages.INVALID_THRESHOLD_MSG,
                    ModerationMessages.INVALID_THRESHOLD);
        }
        checkRange(band.low());
        checkRange(band.high());
        if (band.low() != null && band.high() != null && band.high() < band.low()) {
            // A high below low collapses the review band and turns every
            // borderline case into an auto-block.
            throw new BadRequestException(ModerationMessages.INVALID_THRESHOLD_MSG,
                    ModerationMessages.INVALID_THRESHOLD);
        }
    }

    private static void checkRange(Double value) {
        if (value == null) return;
        if (value.isNaN() || value.isInfinite() || value < 0 || value > 1) {
            throw new BadRequestException(ModerationMessages.INVALID_THRESHOLD_MSG,
                    ModerationMessages.INVALID_THRESHOLD);
        }
    }

    private static ModeratedEntityType parseType(String raw) {
        return ModeratedEntityType.parse(raw)
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.INVALID_MODERATION_SETTING_MSG.formatted(raw),
                        ModerationMessages.INVALID_MODERATION_SETTING));
    }
}
