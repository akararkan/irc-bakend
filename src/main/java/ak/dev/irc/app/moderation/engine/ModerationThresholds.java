package ak.dev.irc.app.moderation.engine;

import ak.dev.irc.app.moderation.enums.ModerationLabel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The resolved two-threshold band per label for one entity type
 * (MODERATION_ROADMAP.md §8.1).
 *
 * <p>Passed into {@link ModerationDecisionEngine} rather than resolved inside it,
 * so the admin dashboard can dry-run a proposed threshold set against real
 * stored scores without persisting anything — the same shape the feed-ranking
 * preview endpoint uses for its knobs.</p>
 *
 * @param bands per-label {@code (low, high)} cut points; a label missing here is
 *              treated as never triggering, which is the safe reading of "the
 *              admin removed this label's rule"
 */
public record ModerationThresholds(Map<ModerationLabel, Band> bands) {

    /**
     * @param low  at/above this the content needs a human
     * @param high at/above this the content is auto-blocked
     */
    public record Band(double low, double high) {

        public Band {
            // A high below low would make the auto-block band swallow the review
            // band and silently turn every borderline case into a rejection.
            if (high < low) high = low;
        }
    }

    public static ModerationThresholds of(Map<ModerationLabel, Band> bands) {
        return new ModerationThresholds(new EnumMap<>(bands));
    }

    public Band band(ModerationLabel label) {
        return bands.get(label);
    }
}
