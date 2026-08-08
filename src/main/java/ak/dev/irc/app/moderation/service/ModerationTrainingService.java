package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.client.InferenceUnavailableException;
import ak.dev.irc.app.moderation.client.ModerationInferenceClient;
import ak.dev.irc.app.moderation.client.ModerationTrainingClient;
import ak.dev.irc.app.moderation.engine.ModerationSettingsService;
import ak.dev.irc.app.moderation.entity.ModerationGoldenCase;
import ak.dev.irc.app.moderation.entity.ModerationModelVersion;
import ak.dev.irc.app.moderation.entity.ModerationTrainingExample;
import ak.dev.irc.app.moderation.enums.ModelVersionStatus;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.TrainingExampleSource;
import ak.dev.irc.app.moderation.repository.ModerationGoldenCaseRepository;
import ak.dev.irc.app.moderation.repository.ModerationModelVersionRepository;
import ak.dev.irc.app.moderation.repository.ModerationTrainingExampleRepository;
import ak.dev.irc.app.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The continuous-learning loop of MODERATION_ROADMAP.md §12.3, §12.4 and §13:
 * dataset curation, retraining, evaluation, promotion and rollback.
 *
 * <p>Spring Boot owns the dataset and the registry; the Python container only
 * turns rows into weights and reports metrics. That split is what keeps rollback
 * cheap — every artifact stays on the shared volume, so re-promoting a retired
 * version is a config flip, never a retrain (§12.4).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationTrainingService {

    /**
     * A bare word is a weak signal for a sentence-level classifier, so §12.3
     * wraps it into short template sentences. The raw word is still stored for
     * the blocklist layer, which is the instant lever; this is the slow one.
     */
    private static final List<String> WORD_TEMPLATES = List.of(
            "You are a %s.",
            "What a %s.",
            "%s");

    private final ModerationTrainingExampleRepository exampleRepository;
    private final ModerationGoldenCaseRepository goldenRepository;
    private final ModerationModelVersionRepository versionRepository;
    private final ModerationTrainingClient trainingClient;
    private final ModerationInferenceClient inferenceClient;
    private final ModerationSettingsService settings;
    private final ModerationProperties properties;
    private final ObjectMapper objectMapper;

    // ── dataset curation (§12.3) ────────────────────────────────────────

    /** Adds one labeled sentence. Re-adding the same text updates its labels. */
    @Transactional
    public ModerationTrainingExample addExample(String text, Map<String, ?> labels,
                                                TrainingExampleSource source, UUID caseId,
                                                String note) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException(ModerationMessages.INVALID_TRAINING_EXAMPLE_MSG,
                    ModerationMessages.INVALID_TRAINING_EXAMPLE);
        }
        String clean = text.trim();
        String hash = hash(clean);

        ModerationTrainingExample example = exampleRepository.findByTextHash(hash)
                .orElseGet(() -> ModerationTrainingExample.builder()
                        .text(clean)
                        .textHash(hash)
                        .build());
        example.applyLabels(labels);
        example.setSource(source);
        example.setCaseId(caseId);
        example.setNote(note);
        example.setAddedBy(SecurityUtils.getCurrentUserId().orElse(null));
        // A re-labelled row has to be re-learned, so it counts as untrained again.
        example.setTrainedInVersion(null);
        return exampleRepository.save(example);
    }

    /**
     * "Add single word" (§12.3). Expands the word into a couple of template
     * sentences so the sentence-level classifier has something to learn from,
     * and stores the bare word too — a moderator adding a slur reasonably
     * expects the literal token in the dataset as well.
     */
    @Transactional
    public List<ModerationTrainingExample> addWord(String word, Map<String, ?> labels, String note) {
        return addWord(word, labels, note, TrainingExampleSource.ADMIN_MANUAL);
    }

    @Transactional
    public List<ModerationTrainingExample> addWord(String word, Map<String, ?> labels, String note,
                                                   TrainingExampleSource source) {
        if (word == null || word.isBlank()) {
            throw new BadRequestException(ModerationMessages.INVALID_TRAINING_EXAMPLE_MSG,
                    ModerationMessages.INVALID_TRAINING_EXAMPLE);
        }
        String clean = word.trim();
        return WORD_TEMPLATES.stream()
                .map(template -> addExample(template.formatted(clean), labels,
                        source, null,
                        note == null ? "expanded from word: " + clean : note))
                .toList();
    }

    /** Whether a row with this (normalised) text is already in the dataset. */
    @Transactional(readOnly = true)
    public boolean existsByText(String text) {
        return text != null && !text.isBlank()
                && exampleRepository.findByTextHash(hash(text.trim())).isPresent();
    }

    /** Golden regression case (§17). Never trained on — see the entity javadoc. */
    @Transactional
    public ModerationGoldenCase addGoldenCase(String text, Map<String, ?> labels, String note) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException(ModerationMessages.INVALID_TRAINING_EXAMPLE_MSG,
                    ModerationMessages.INVALID_TRAINING_EXAMPLE);
        }
        String clean = text.trim();
        String hash = hash(clean);
        ModerationGoldenCase goldenCase = goldenRepository.findByTextHash(hash)
                .orElseGet(() -> ModerationGoldenCase.builder().text(clean).textHash(hash).build());
        goldenCase.setToxic(flag(labels, ModerationLabel.TOXIC));
        goldenCase.setSevereToxic(flag(labels, ModerationLabel.SEVERE_TOXIC));
        goldenCase.setObscene(flag(labels, ModerationLabel.OBSCENE));
        goldenCase.setThreat(flag(labels, ModerationLabel.THREAT));
        goldenCase.setInsult(flag(labels, ModerationLabel.INSULT));
        goldenCase.setIdentityHate(flag(labels, ModerationLabel.IDENTITY_HATE));
        goldenCase.setNote(note);
        goldenCase.setAddedBy(SecurityUtils.getCurrentUserId().orElse(null));
        return goldenRepository.save(goldenCase);
    }

    // ── retraining (§12.4) ──────────────────────────────────────────────

    /**
     * Starts a fine-tune. Returns immediately with a {@code TRAINING} registry
     * row; the job itself runs in the Python container and is chased by
     * {@link #pollRunningJobs()} or by the completion webhook, whichever lands
     * first.
     */
    @Transactional
    public ModerationModelVersion startRetrain(String baseVersion, String notes) {
        if (versionRepository.existsByStatusIn(
                List.of(ModelVersionStatus.TRAINING, ModelVersionStatus.EVALUATING))) {
            throw new BadRequestException(ModerationMessages.TRAINING_ALREADY_RUNNING_MSG,
                    ModerationMessages.TRAINING_ALREADY_RUNNING);
        }

        long total = exampleRepository.count();
        int minimum = properties.getRetrain().getMinExamples();
        if (total < minimum) {
            throw new BadRequestException(
                    ModerationMessages.TRAINING_DATASET_TOO_SMALL_MSG.formatted(total, minimum),
                    ModerationMessages.TRAINING_DATASET_TOO_SMALL);
        }

        List<ModerationTrainingExample> examples = exampleRepository.exportAll(
                PageRequest.of(0, properties.getTraining().getMaxExamples()));
        List<ModerationGoldenCase> golden = goldenRepository.findAll();
        if (examples.size() < total) {
            log.warn("[MODERATION] training set capped at {} of {} examples "
                            + "(app.moderation.training.max-examples)",
                    examples.size(), total);
        }

        String checkpoint = baseVersion != null && !baseVersion.isBlank()
                ? baseVersion
                : activeVersion().map(ModerationModelVersion::getVersion).orElse(null);

        ModerationTrainingClient.StartedJob job;
        try {
            job = trainingClient.startTraining(examples, golden, checkpoint, notes);
        } catch (InferenceUnavailableException down) {
            throw new BadRequestException(ModerationMessages.TRAINING_SERVICE_UNAVAILABLE_MSG,
                    ModerationMessages.TRAINING_SERVICE_UNAVAILABLE);
        }

        return versionRepository.save(ModerationModelVersion.builder()
                .version("job-" + job.jobId())   // replaced by the real version on completion
                .status(ModelVersionStatus.TRAINING)
                .jobId(job.jobId())
                .baseCheckpoint(checkpoint)
                .trainingExamplesCount(examples.size())
                .notes(notes)
                .requestedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .build());
    }

    /**
     * Chases in-flight jobs. Called by the scheduled poller and safe to call from
     * the admin UI; the webhook path lands on {@link #ingestJobResult} too, so
     * whichever arrives first wins and the other is a no-op.
     */
    @Transactional
    public int pollRunningJobs() {
        List<ModerationModelVersion> running = versionRepository.findByStatusIn(
                List.of(ModelVersionStatus.TRAINING, ModelVersionStatus.EVALUATING));
        int settled = 0;
        for (ModerationModelVersion version : running) {
            if (version.getJobId() == null) continue;
            try {
                JsonNode status = trainingClient.jobStatus(version.getJobId());
                if (ingestJobResult(status)) settled++;
            } catch (Exception ex) {
                // The training container keeps its job registry in memory, so a
                // restart makes the id permanently unknown (404). Left alone the
                // row would sit at TRAINING forever and block every future retrain
                // on TRAINING_ALREADY_RUNNING, so a 404 is treated as terminal.
                if (String.valueOf(ex.getMessage()).contains("HTTP 404")) {
                    version.setStatus(ModelVersionStatus.FAILED);
                    version.setError("training container lost the job (restarted?) — job id "
                            + version.getJobId() + " is unknown");
                    version.setCompletedAt(LocalDateTime.now());
                    versionRepository.save(version);
                    settled++;
                    log.warn("[MODERATION] training job {} is unknown to the container — marked FAILED",
                            version.getJobId());
                } else {
                    log.debug("[MODERATION] poll of job {} failed: {}",
                            version.getJobId(), ex.getMessage());
                }
            }
        }
        return settled;
    }

    /**
     * Folds a training-service job payload into the registry. Shared by the
     * poller and the webhook.
     *
     * @return true when the row reached a terminal state on this call
     */
    @Transactional
    public boolean ingestJobResult(JsonNode payload) {
        if (payload == null) return false;
        String jobId = payload.path("job_id").asText(null);
        if (jobId == null) return false;

        ModerationModelVersion version = versionRepository.findByStatusIn(
                        List.of(ModelVersionStatus.TRAINING, ModelVersionStatus.EVALUATING)).stream()
                .filter(row -> jobId.equals(row.getJobId()))
                .findFirst()
                .orElse(null);
        if (version == null) return false;   // already settled

        String status = payload.path("status").asText("");
        switch (status) {
            case "training" -> version.setStatus(ModelVersionStatus.TRAINING);
            case "evaluating" -> version.setStatus(ModelVersionStatus.EVALUATING);
            case "failed" -> {
                version.setStatus(ModelVersionStatus.FAILED);
                version.setError(truncate(payload.path("error").asText("training failed"), 500));
                version.setCompletedAt(LocalDateTime.now());
                versionRepository.save(version);
                log.error("[MODERATION] training job {} failed: {}", jobId, version.getError());
                return true;
            }
            case "done" -> {
                completeVersion(version, payload);
                return true;
            }
            default -> {
                return false;
            }
        }
        versionRepository.save(version);
        return false;
    }

    private void completeVersion(ModerationModelVersion version, JsonNode payload) {
        version.setVersion(payload.path("version").asText(version.getVersion()));
        version.setArtifactPath(payload.path("artifact_path").asText(null));
        version.setBaseCheckpoint(payload.path("base_checkpoint").asText(version.getBaseCheckpoint()));
        version.setTrainingExamplesCount(payload.path("train_count").asInt(version.getTrainingExamplesCount()));
        version.setValidationCount(payload.path("validation_count").asInt(0));
        version.setEvalMetricsJson(payload.path("metrics").toString());
        version.setGoldenReportJson(payload.path("golden").toString());
        version.setMacroF1(payload.path("metrics").path("macro_f1").asDouble(0));
        version.setCompletedAt(LocalDateTime.now());

        GateResult gate = evaluateGate(payload);
        version.setGatePassed(gate.passed());
        version.setGateDetail(truncate(gate.detail(), 500));
        // READY even when the gate fails: the row is the evidence a human needs
        // to decide, and FAILED would imply the run itself broke. Promotion is
        // what the gate actually blocks.
        version.setStatus(ModelVersionStatus.READY);
        versionRepository.save(version);

        exampleRepository.markTrained(version.getVersion());
        log.info("[MODERATION] training job {} produced {} — macroF1={} gate={}",
                version.getJobId(), version.getVersion(), version.getMacroF1(), gate.detail());
    }

    /**
     * The §12.4 promotion bar: no per-label F1 may drop more than
     * {@code app.moderation.retrain.max-f1-drop} versus the active version, and
     * every golden case must still pass.
     *
     * <p>Labels the candidate never evaluated (no positives in the validation
     * fold) are skipped rather than scored zero — comparing against a metric
     * that was never measured would fail every gate on a small dataset for no
     * real reason.</p>
     */
    private GateResult evaluateGate(JsonNode payload) {
        JsonNode goldenNode = payload.path("golden");
        int goldenFailed = goldenNode.path("failed").asInt(0);
        if (goldenFailed > 0) {
            return new GateResult(false, "%d golden regression case(s) failed".formatted(goldenFailed));
        }

        Optional<ModerationModelVersion> active = activeVersion();
        if (active.isEmpty() || active.get().getEvalMetricsJson() == null) {
            return new GateResult(true, "no active baseline to compare against");
        }

        try {
            JsonNode baseline = objectMapper.readTree(active.get().getEvalMetricsJson())
                    .path("per_label");
            JsonNode candidate = payload.path("metrics").path("per_label");
            double allowed = settings.retrainMaxF1Drop();

            for (ModerationLabel label : ModerationLabel.values()) {
                JsonNode candidateLabel = candidate.path(label.wire());
                JsonNode baselineLabel = baseline.path(label.wire());
                if (!candidateLabel.path("evaluated").asBoolean(false)
                        || !baselineLabel.path("evaluated").asBoolean(false)) {
                    continue;
                }
                double drop = baselineLabel.path("f1").asDouble(0) - candidateLabel.path("f1").asDouble(0);
                if (drop > allowed) {
                    return new GateResult(false, "%s F1 dropped %.3f (limit %.3f)"
                            .formatted(label.wire(), drop, allowed));
                }
            }
            return new GateResult(true, "no per-label F1 drop beyond %.3f".formatted(allowed));
        } catch (Exception ex) {
            return new GateResult(false, "could not compare metrics: " + ex.getMessage());
        }
    }

    // ── promotion / rollback (§12.4) ────────────────────────────────────

    /**
     * Points the inference container at this artifact and retires the previous
     * active version.
     *
     * @param force skip the promotion gate — a deliberate override an admin has
     *              to opt into, recorded in the audit trail by the caller
     */
    @Transactional
    public ModerationModelVersion promote(UUID versionId, boolean force) {
        ModerationModelVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.MODEL_VERSION_NOT_FOUND_MSG.formatted(versionId),
                        ModerationMessages.MODEL_VERSION_NOT_FOUND));

        if (version.getStatus() != ModelVersionStatus.READY
                && version.getStatus() != ModelVersionStatus.SHADOW
                && version.getStatus() != ModelVersionStatus.RETIRED) {
            throw new BadRequestException(
                    ModerationMessages.MODEL_NOT_PROMOTABLE_MSG.formatted(version.getStatus()),
                    ModerationMessages.MODEL_NOT_PROMOTABLE);
        }
        if (!force && Boolean.FALSE.equals(version.getGatePassed())) {
            throw new BadRequestException(
                    ModerationMessages.MODEL_GATE_FAILED_MSG.formatted(version.getGateDetail()),
                    ModerationMessages.MODEL_GATE_FAILED);
        }

        // Reload BEFORE flipping the registry: if the container refuses the
        // artifact, the database must not claim it is live.
        try {
            inferenceClient.reload(version.getVersion());
        } catch (InferenceUnavailableException down) {
            throw new BadRequestException(ModerationMessages.INFERENCE_UNAVAILABLE_MSG,
                    ModerationMessages.INFERENCE_UNAVAILABLE);
        }

        activeVersion().filter(previous -> !previous.getId().equals(version.getId()))
                .ifPresent(previous -> {
                    previous.setStatus(ModelVersionStatus.RETIRED);
                    versionRepository.save(previous);
                });

        version.setStatus(ModelVersionStatus.ACTIVE);
        version.setPromotedAt(LocalDateTime.now());
        version.setPromotedBy(SecurityUtils.getCurrentUserId().orElse(null));
        log.info("[MODERATION] promoted model {} to ACTIVE{}", version.getVersion(),
                force ? " (gate overridden)" : "");
        return versionRepository.save(version);
    }

    /** Marks a candidate as shadow — scored but not enforced (§12.4). */
    @Transactional
    public ModerationModelVersion markShadow(UUID versionId) {
        ModerationModelVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.MODEL_VERSION_NOT_FOUND_MSG.formatted(versionId),
                        ModerationMessages.MODEL_VERSION_NOT_FOUND));
        version.setStatus(ModelVersionStatus.SHADOW);
        return versionRepository.save(version);
    }

    /**
     * Re-points at the most recently retired version — the safety valve for a
     * retrain that regressed. Never depends on re-running training because every
     * artifact stays in the shared volume.
     */
    @Transactional
    public ModerationModelVersion rollback() {
        ModerationModelVersion previous = versionRepository
                .findByStatus(ModelVersionStatus.RETIRED).stream()
                .filter(row -> row.getPromotedAt() != null)
                .max((a, b) -> a.getPromotedAt().compareTo(b.getPromotedAt()))
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.MODEL_VERSION_NOT_FOUND_MSG.formatted("no retired version"),
                        ModerationMessages.MODEL_VERSION_NOT_FOUND));
        return promote(previous.getId(), true);
    }

    public Optional<ModerationModelVersion> activeVersion() {
        return versionRepository.findByStatus(ModelVersionStatus.ACTIVE).stream().findFirst();
    }

    /** Dataset shape for the §12.3 browser header. */
    @Transactional(readOnly = true)
    public Map<String, Object> datasetSummary() {
        List<Object[]> totals = exampleRepository.labelTotals();
        Object[] row = totals.isEmpty() ? new Object[7] : totals.get(0);
        return Map.of(
                "total", num(row.length > 6 ? row[6] : null),
                "untrained", exampleRepository.countByTrainedInVersionIsNull(),
                "goldenCases", goldenRepository.count(),
                "labelTotals", Map.of(
                        "toxic", num(row[0]),
                        "severe_toxic", num(row[1]),
                        "obscene", num(row[2]),
                        "threat", num(row[3]),
                        "insult", num(row[4]),
                        "identity_hate", num(row[5])),
                "bySource", java.util.Arrays.stream(TrainingExampleSource.values())
                        .collect(java.util.stream.Collectors.toMap(Enum::name,
                                exampleRepository::countBySource)));
    }

    private record GateResult(boolean passed, String detail) {
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static short flag(Map<String, ?> labels, ModerationLabel label) {
        if (labels == null) return 0;
        Object value = labels.get(label.wire());
        if (value == null) return 0;
        if (value instanceof Boolean b) return (short) (b ? 1 : 0);
        if (value instanceof Number n) return (short) (n.intValue() > 0 ? 1 : 0);
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return (short) ("1".equals(text) || "true".equals(text) ? 1 : 0);
    }

    /**
     * Dedup key for the dataset. Case- and whitespace-insensitive so the same
     * sentence promoted from two different review rows lands once — an
     * accidentally duplicated example silently doubles its own weight during
     * training.
     */
    private static String hash(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
