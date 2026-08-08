package ak.dev.irc.app.moderation.client;

import ak.dev.irc.app.moderation.enums.ModerationLabel;

import java.util.EnumMap;
import java.util.Map;

/**
 * One field's raw label probabilities as returned by {@code POST /v1/score} or
 * one entry of {@code /v1/score/batch}.
 *
 * <p>Deliberately carries no verdict. The inference container is a pure scorer
 * (MODERATION_ROADMAP.md §0.2) — turning these numbers into approve/review/block
 * is the Decision Engine's job, and keeping that split is what lets thresholds
 * move without touching the model container.</p>
 *
 * @param fieldId      the id echoed back by the batch endpoint
 * @param scores       per-label sigmoid outputs, 0..1
 * @param modelVersion artifact version that produced them
 * @param inferenceMs  container-side latency, for the SLA panel
 */
public record ScoreResult(String fieldId,
                          Map<ModerationLabel, Double> scores,
                          String modelVersion,
                          double inferenceMs) {

    public static ScoreResult empty(String fieldId, String modelVersion) {
        return new ScoreResult(fieldId, new EnumMap<>(ModerationLabel.class), modelVersion, 0);
    }

    public double score(ModerationLabel label) {
        Double value = scores.get(label);
        return value == null ? 0.0 : value;
    }
}
