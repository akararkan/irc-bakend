package ak.dev.irc.app.moderation.enums;

/**
 * The three-band output of the Decision Engine (MODERATION_ROADMAP.md §8.1),
 * computed per field and then aggregated to the entity as the worst of its
 * fields (§5.4).
 */
public enum ModerationVerdict {

    /** Every label below its {@code low} threshold. */
    APPROVE(0),

    /** Some label between {@code low} and {@code high}, or a soft blocklist flag. */
    REVIEW(1),

    /** Some label at/above its {@code high} threshold, or a hard blocklist hit. */
    REJECT(2);

    private final int severity;

    ModerationVerdict(int severity) {
        this.severity = severity;
    }

    /** Entity verdict = the worst of its field verdicts (§5.4). */
    public ModerationVerdict worstOf(ModerationVerdict other) {
        if (other == null) return this;
        return other.severity > this.severity ? other : this;
    }

    public ModerationStatus toStatus() {
        return switch (this) {
            case APPROVE -> ModerationStatus.APPROVED;
            case REVIEW -> ModerationStatus.IN_REVIEW;
            case REJECT -> ModerationStatus.REJECTED;
        };
    }
}
