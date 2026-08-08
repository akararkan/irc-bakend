package ak.dev.irc.app.moderation.enums;

/**
 * Lifecycle of one trained artifact in the model registry
 * (MODERATION_ROADMAP.md §12.4). A retrain never promotes itself — the
 * transition to {@link #ACTIVE} always requires a human click, gated on the
 * evaluation metrics clearing the configured bar.
 */
public enum ModelVersionStatus {

    /** The training job is running. */
    TRAINING,

    /** Training finished; held-out + golden-set evaluation in progress. */
    EVALUATING,

    /** Evaluated and available to promote. */
    READY,

    /** Scoring live traffic in parallel with {@link #ACTIVE}; decisions logged, not enforced. */
    SHADOW,

    /** The version the inference service is currently serving. */
    ACTIVE,

    /** Previously active. Kept so rollback never needs a retrain. */
    RETIRED,

    /** The job died, or evaluation fell below the promotion bar. */
    FAILED
}
