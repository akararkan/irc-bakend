package ak.dev.irc.app.moderation.engine;

import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.ModerationVerdict;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Turns raw label probabilities into a three-band verdict
 * (MODERATION_ROADMAP.md §8.1). This is the whole reason the inference container
 * returns numbers and not decisions: policy lives here, in Java, backed by a
 * settings table an admin can edit — so retuning sensitivity never touches the
 * model container (§0.2).
 *
 * <p>Pure and stateless. Thresholds are a parameter, not a lookup, which is what
 * makes the dashboard's dry-run endpoint possible: the same code path scores a
 * stored case against a proposed threshold set without persisting anything.</p>
 */
@Service
public class ModerationDecisionEngine {

    /**
     * Bands are evaluated worst-label-wins: one label at/above its {@code high}
     * blocks the field even if every other label is clean, because "this text is
     * fine except for the death threat" is not a passing grade.
     *
     * <p>Comparisons are {@code >=} on both edges. A score exactly equal to
     * {@code high} auto-blocks and one exactly equal to {@code low} goes to
     * review — the stricter reading of each boundary, and the one the unit tests
     * in §17 assert.</p>
     */
    public FieldDecision decide(Map<ModerationLabel, Double> scores, ModerationThresholds thresholds) {
        if (scores == null || scores.isEmpty()) {
            return FieldDecision.approved();
        }

        ModerationVerdict verdict = ModerationVerdict.APPROVE;
        ModerationLabel topLabel = null;
        double topScore = 0.0;
        // "Top" means the label that most drives the verdict, not simply the
        // highest number: a threat at 0.55 that crosses its 0.50 block bar
        // matters more than an insult at 0.70 that does not cross its 0.80 bar.
        int topRank = -1;

        for (Map.Entry<ModerationLabel, Double> entry : scores.entrySet()) {
            ModerationThresholds.Band band = thresholds.band(entry.getKey());
            if (band == null) continue;

            double score = entry.getValue() == null ? 0.0 : entry.getValue();
            ModerationVerdict labelVerdict;
            if (score >= band.high()) {
                labelVerdict = ModerationVerdict.REJECT;
            } else if (score >= band.low()) {
                labelVerdict = ModerationVerdict.REVIEW;
            } else {
                labelVerdict = ModerationVerdict.APPROVE;
            }

            verdict = verdict.worstOf(labelVerdict);
            int rank = labelVerdict.ordinal();
            if (rank > topRank || (rank == topRank && score > topScore)) {
                topRank = rank;
                topLabel = entry.getKey();
                topScore = score;
            }
        }

        return new FieldDecision(verdict, topLabel, topScore, scores, null);
    }

    /**
     * Combines a model decision with a deny-list outcome for the same field. The
     * blocklist can only ever make a field <em>more</em> restricted — a soft flag
     * forces review even when the model would have approved (§8.2), and a hard
     * block short-circuits before the model is called at all.
     */
    public FieldDecision merge(FieldDecision model, FieldDecision blocklist) {
        if (blocklist == null) return model;
        if (model == null) return blocklist;
        return new FieldDecision(
                model.verdict().worstOf(blocklist.verdict()),
                model.topLabel(),
                model.topScore(),
                model.scores(),
                blocklist.blocklistHit() != null ? blocklist.blocklistHit() : model.blocklistHit());
    }
}
