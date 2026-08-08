package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.moderation.client.ModerationInferenceClient;
import ak.dev.irc.app.moderation.client.ModerationTrainingClient;
import ak.dev.irc.app.moderation.engine.ModerationSettingsService;
import ak.dev.irc.app.moderation.entity.ModerationModelVersion;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.repository.ModerationGoldenCaseRepository;
import ak.dev.irc.app.moderation.repository.ModerationTrainingExampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The §12.5 analytics panel: volume, band breakdown, SLA compliance, label
 * distribution and model health, in one call.
 *
 * <p>The number worth watching is the auto-decided share. A healthy system
 * settles the large majority of traffic without a human; if {@code IN_REVIEW}
 * volume creeps up, either the thresholds are too tight or the model has
 * drifted — and this is the panel that says which.</p>
 */
@Service
@RequiredArgsConstructor
public class ModerationMetricsService {

    private final ModerationCaseRepository caseRepository;
    private final ModerationTrainingExampleRepository exampleRepository;
    private final ModerationGoldenCaseRepository goldenRepository;
    private final ModerationInferenceClient inferenceClient;
    private final ModerationTrainingClient trainingClient;
    private final ModerationTrainingService trainingService;
    private final ModerationSettingsService settings;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(int windowHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, windowHours));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", windowHours);
        out.put("enabled", settings.globallyEnabled());
        out.put("queue", queueCounts());
        out.put("volume", volumeByType(since));
        out.put("bands", bandBreakdown(since));
        out.put("labels", labelBreakdown(since));
        out.put("sla", slaBreakdown(since));
        out.put("model", modelHealth());
        out.put("dataset", datasetHealth());
        return out;
    }

    /** Live queue depth — what a moderator is looking at right now. */
    @Transactional(readOnly = true)
    public Map<String, Object> queueCounts() {
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("inReview", caseRepository.countByStatus(ModerationStatus.IN_REVIEW));
        queue.put("pending", caseRepository.countByStatus(ModerationStatus.PENDING));
        queue.put("slaBreached",
                caseRepository.countByStatusAndSlaBreached(ModerationStatus.IN_REVIEW, true));
        return queue;
    }

    private Map<String, Object> volumeByType(LocalDateTime since) {
        Map<String, Map<String, Long>> byType = new LinkedHashMap<>();
        for (ModeratedEntityType type : ModeratedEntityType.values()) {
            byType.put(type.key(), new LinkedHashMap<>());
        }
        for (Object[] row : caseRepository.countByTypeAndStatusSince(since)) {
            ModeratedEntityType type = (ModeratedEntityType) row[0];
            ModerationStatus status = (ModerationStatus) row[1];
            byType.get(type.key()).put(status.name(), ((Number) row[2]).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", caseRepository.countBySubmittedAtAfter(since));
        out.put("byEntityType", byType);
        return out;
    }

    /**
     * How much of the traffic the system decided on its own. {@code decidedBy}
     * is {@code "system"} for an automated verdict and an admin id otherwise, so
     * this is a clean split without a second column.
     */
    private Map<String, Object> bandBreakdown(LocalDateTime since) {
        long autoApproved = 0;
        long autoRejected = 0;
        long autoReview = 0;
        long human = 0;

        for (Object[] row : caseRepository.countByActorAndStatusSince(since)) {
            String actor = row[0] == null ? "system" : String.valueOf(row[0]);
            ModerationStatus status = (ModerationStatus) row[1];
            long count = ((Number) row[2]).longValue();
            if ("system".equals(actor)) {
                switch (status) {
                    case APPROVED -> autoApproved += count;
                    case REJECTED -> autoRejected += count;
                    case IN_REVIEW -> autoReview += count;
                    default -> { }
                }
            } else {
                human += count;
            }
        }

        long decided = autoApproved + autoRejected + autoReview + human;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoApproved", autoApproved);
        out.put("autoRejected", autoRejected);
        out.put("sentToReview", autoReview);
        out.put("decidedByHuman", human);
        out.put("autoDecidedPercent", decided == 0 ? 0
                : Math.round((autoApproved + autoRejected) * 1000.0 / decided) / 10.0);
        return out;
    }

    private List<Map<String, Object>> labelBreakdown(LocalDateTime since) {
        return caseRepository.labelBreakdownSince(since).stream()
                .map(row -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", row[0]);
                    entry.put("count", ((Number) row[1]).longValue());
                    entry.put("avgScore", row[2] == null ? 0
                            : Math.round(((Number) row[2]).doubleValue() * 10000) / 10000.0);
                    return entry;
                })
                .toList();
    }

    private List<Map<String, Object>> slaBreakdown(LocalDateTime since) {
        return caseRepository.slaBreakdownSince(since).stream()
                .map(row -> {
                    long total = ((Number) row[1]).longValue();
                    long breached = row[2] == null ? 0 : ((Number) row[2]).longValue();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("entityType", ((ModeratedEntityType) row[0]).key());
                    entry.put("total", total);
                    entry.put("breached", breached);
                    entry.put("withinSlaPercent",
                            total == 0 ? 100.0 : Math.round((total - breached) * 1000.0 / total) / 10.0);
                    return entry;
                })
                .toList();
    }

    /** Inference/training container health plus the currently active artifact. */
    public Map<String, Object> modelHealth() {
        ModerationInferenceClient.Health health = inferenceClient.health();
        ModerationInferenceClient.Stats stats = inferenceClient.stats();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inferenceUp", health.up());
        out.put("inferenceError", health.error());
        out.put("residentVersion", health.modelVersion());
        out.put("circuit", stats.circuit());
        out.put("calls", stats.calls());
        out.put("failures", stats.failures());
        out.put("avgLatencyMs", stats.avgLatencyMs());
        out.put("lastError", stats.lastError());
        out.put("trainingUp", trainingClient.health().up());

        trainingService.activeVersion().ifPresent(active -> {
            Map<String, Object> registry = new LinkedHashMap<>();
            registry.put("version", active.getVersion());
            registry.put("macroF1", active.getMacroF1());
            registry.put("promotedAt", active.getPromotedAt());
            registry.put("trainingExamples", active.getTrainingExamplesCount());
            out.put("activeVersion", registry);
        });
        // A resident artifact that is not the one the registry calls active means
        // a promote never reached the container, or someone rolled it by hand.
        out.put("registryInSync", trainingService.activeVersion()
                .map(ModerationModelVersion::getVersion)
                .map(version -> version.equals(health.modelVersion()))
                .orElse(true));
        return out;
    }

    private Map<String, Object> datasetHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examples", exampleRepository.count());
        out.put("untrained", exampleRepository.countByTrainedInVersionIsNull());
        out.put("goldenCases", goldenRepository.count());
        return out;
    }
}
