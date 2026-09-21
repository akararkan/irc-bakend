package ak.dev.irc.app.moderation.image;

import ak.dev.irc.app.moderation.enums.ModerationVerdict;

/**
 * The gate's answer for one screened image. REJECT never appears here — a
 * confident block throws inside the gate before anything is stored — so a
 * returned result is always publishable; {@link #needsReview()} says whether it
 * additionally belongs in the human queue.
 *
 * @param nsfwScore  null when the image was not actually scored (moderation
 *                   disabled, undecodable bytes, or scorer down under
 *                   FAIL_OPEN_SHADOW)
 * @param scored     false on any of those unscored paths
 * @param reasonCode machine-readable why, mirroring {@code ModerationCase}:
 *                   {@code MODEL} or {@code MODEL_UNAVAILABLE}
 */
public record ImageScreenResult(ModerationVerdict verdict, Double nsfwScore,
                                String modelVersion, boolean scored, String reasonCode) {

    /** Moderation off / unscorable bytes: publish, no queue entry. */
    static ImageScreenResult skipped() {
        return new ImageScreenResult(ModerationVerdict.APPROVE, null, null, false, null);
    }

    public boolean needsReview() {
        return verdict == ModerationVerdict.REVIEW;
    }
}
