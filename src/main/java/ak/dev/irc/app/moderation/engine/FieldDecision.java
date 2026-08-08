package ak.dev.irc.app.moderation.engine;

import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.ModerationVerdict;

import java.util.Map;

/**
 * The Decision Engine's answer for one text field.
 *
 * @param verdict      approve / review / block for this field alone
 * @param topLabel     the label that drove the verdict, null when nothing scored
 * @param topScore     that label's probability
 * @param scores       the full raw vector, stored verbatim so every decision
 *                     stays explainable and reversible (§3.3)
 * @param blocklistHit normalized deny-list term that matched, if any (§8.2)
 */
public record FieldDecision(ModerationVerdict verdict,
                            ModerationLabel topLabel,
                            double topScore,
                            Map<ModerationLabel, Double> scores,
                            String blocklistHit) {

    public static FieldDecision approved() {
        return new FieldDecision(ModerationVerdict.APPROVE, null, 0, Map.of(), null);
    }

    /** A hard deny-list hit — no model call was made, and none is needed (§8.2). */
    public static FieldDecision hardBlocked(String term) {
        return new FieldDecision(ModerationVerdict.REJECT, null, 1.0, Map.of(), term);
    }

    /** A soft deny-list flag: publishable by the model, but a human should look (§8.2). */
    public static FieldDecision softFlagged(String term) {
        return new FieldDecision(ModerationVerdict.REVIEW, null, 0, Map.of(), term);
    }
}
