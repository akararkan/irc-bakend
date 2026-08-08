package ak.dev.irc.app.moderation.enums;

/**
 * Where a labeled training row came from (MODERATION_ROADMAP.md §9,
 * {@code training_examples.source}). Admin corrections are the highest-quality
 * signal the system can get (§3.4), so keeping the provenance lets the dataset
 * browser filter and weight by it.
 */
public enum TrainingExampleSource {

    /** Shipped with the model artifact; the original hand-written examples. */
    SEED_DATASET,

    /** An admin typed a word or sentence into the dashboard (§12.3). */
    ADMIN_MANUAL,

    /** An admin overturned an automated verdict — the model was wrong here. */
    ADMIN_CORRECTION,

    /** Promoted from a review-queue decision that agreed with the model. */
    REVIEW_PROMOTION,

    /** A user report a moderator confirmed. */
    USER_REPORT_CONFIRMED,

    /** Bulk-imported from an admin-curated CSV file (§12.3 import surface). */
    ADMIN_IMPORT
}
