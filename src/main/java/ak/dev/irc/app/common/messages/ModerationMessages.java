package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the automated text-moderation system
 * ({@code app/moderation}, {@code app/admin/moderation}).
 * Catalog: docs/errors/user-facing-messages.md §1 {@code moderation/*} tables,
 * §2 notes, §3 notifications. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 *
 * <p>Rejection copy deliberately never quotes the offending text back or names
 * the label that fired. Telling an author exactly which word tripped which
 * threshold hands them a free oracle for probing the classifier, and §15's
 * privacy stance keeps model internals inside the moderation surface.</p>
 */
public final class ModerationMessages {

    private ModerationMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String CONTENT_REJECTED            = "CONTENT_REJECTED";
    public static final String CONTENT_UNDER_REVIEW        = "CONTENT_UNDER_REVIEW";
    public static final String MODERATION_CASE_NOT_FOUND   = "MODERATION_CASE_NOT_FOUND";
    public static final String MODERATION_CASE_DECIDED     = "MODERATION_CASE_DECIDED";
    public static final String INVALID_MODERATION_ACTION   = "INVALID_MODERATION_ACTION";
    public static final String INVALID_MODERATION_SETTING  = "INVALID_MODERATION_SETTING";
    public static final String INVALID_THRESHOLD           = "INVALID_THRESHOLD";
    public static final String INVALID_TRAINING_EXAMPLE    = "INVALID_TRAINING_EXAMPLE";
    public static final String INVALID_IMPORT_FILE          = "INVALID_IMPORT_FILE";
    public static final String TRAINING_DATASET_TOO_SMALL  = "TRAINING_DATASET_TOO_SMALL";
    public static final String TRAINING_ALREADY_RUNNING    = "TRAINING_ALREADY_RUNNING";
    public static final String TRAINING_SERVICE_UNAVAILABLE = "TRAINING_SERVICE_UNAVAILABLE";
    public static final String INFERENCE_UNAVAILABLE       = "INFERENCE_UNAVAILABLE";
    public static final String MODEL_VERSION_NOT_FOUND     = "MODEL_VERSION_NOT_FOUND";
    public static final String MODEL_NOT_PROMOTABLE        = "MODEL_NOT_PROMOTABLE";
    public static final String MODEL_GATE_FAILED           = "MODEL_GATE_FAILED";
    public static final String INVALID_CALLBACK_TOKEN      = "INVALID_CALLBACK_TOKEN";

    // ── message text (templates render with .formatted) ─────────────────

    /** {@code CONTENT_REJECTED} — auto-block, and the admin-reject path. */
    public static final String CONTENT_REJECTED_MSG =
            "This content was blocked because it appears to violate the community guidelines. "
                    + "If you believe this is a mistake, you can appeal from your account settings.";

    /** {@code CONTENT_UNDER_REVIEW} — returned when a caller re-submits something already held. */
    public static final String CONTENT_UNDER_REVIEW_MSG =
            "This content is still being reviewed. You will be notified once a decision is made.";

    public static final String MODERATION_CASE_NOT_FOUND_MSG =
            "Moderation case not found: %s";
    public static final String MODERATION_CASE_DECIDED_MSG =
            "This case has already been decided (%s) and cannot be decided again.";
    public static final String INVALID_MODERATION_ACTION_MSG =
            "Unknown action. Allowed: APPROVE, REJECT.";
    public static final String INVALID_MODERATION_SETTING_MSG =
            "Unknown moderation setting key: %s";
    public static final String INVALID_THRESHOLD_MSG =
            "Thresholds must be between 0 and 1, and 'high' must not be below 'low'.";
    public static final String INVALID_TRAINING_EXAMPLE_MSG =
            "A training example needs non-empty text.";
    /** {@code INVALID_IMPORT_FILE} — %s = what was wrong with the file. */
    public static final String INVALID_IMPORT_FILE_MSG =
            "The import file could not be processed: %s";
    public static final String TRAINING_DATASET_TOO_SMALL_MSG =
            "The training dataset has %s examples; at least %s are needed for a meaningful run.";
    public static final String TRAINING_ALREADY_RUNNING_MSG =
            "A training run is already in progress. Wait for it to finish before starting another.";
    public static final String TRAINING_SERVICE_UNAVAILABLE_MSG =
            "The training service is not reachable. Start the model-training container and try again.";
    public static final String INFERENCE_UNAVAILABLE_MSG =
            "The moderation model service is not reachable.";
    public static final String MODEL_VERSION_NOT_FOUND_MSG =
            "Model version not found: %s";
    public static final String MODEL_NOT_PROMOTABLE_MSG =
            "Only READY, SHADOW or RETIRED versions can be promoted; this one is %s.";
    public static final String MODEL_GATE_FAILED_MSG =
            "This version failed the promotion gate: %s. Override deliberately if you still want it live.";
    public static final String INVALID_CALLBACK_TOKEN_MSG =
            "Invalid training callback token.";

    // ── notes returned inside 200 bodies ────────────────────────────────

    /** {@code note} on a create response whose content is held for review. */
    public static final String NOTE_HELD_FOR_REVIEW =
            "Your content was posted and is being checked automatically. "
                    + "It becomes visible to others as soon as it clears — usually within a few seconds.";

    /** {@code note} when the hold window expired and a human now has it. */
    public static final String NOTE_ESCALATED_TO_REVIEW =
            "Automatic checking took longer than expected, so a moderator is reviewing this. "
                    + "It stays hidden from others until then.";

    /** {@code warning} on the admin settings screen when the model is unreachable. */
    public static final String WARN_INFERENCE_DOWN =
            "The inference service is not answering. Content is following the configured "
                    + "fallback policy per entity type — check the ops board.";

    /** {@code warning} shown when the master switch is off. */
    public static final String WARN_MODERATION_DISABLED =
            "Automated moderation is DISABLED (app.moderation.enabled=false). "
                    + "Only the keyword blocklist is enforced.";

    // ── author notifications (§3) ───────────────────────────────────────

    public static final String NOTIF_CONTENT_REJECTED_TITLE =
            "Your content was removed";
    /** %s = entity label ("post", "comment", …). */
    public static final String NOTIF_CONTENT_REJECTED_BODY =
            "Your %s was removed because it appears to violate the community guidelines. "
                    + "You can appeal this decision from your account settings.";

    public static final String NOTIF_CONTENT_UNDER_REVIEW_TITLE =
            "Your content is being reviewed";
    /** %s = entity label. */
    public static final String NOTIF_CONTENT_UNDER_REVIEW_BODY =
            "Your %s is waiting on a moderator and is not visible to others yet. "
                    + "We will let you know as soon as it is reviewed.";

    public static final String NOTIF_CONTENT_APPROVED_TITLE =
            "Your content is live";
    /** %s = entity label. */
    public static final String NOTIF_CONTENT_APPROVED_BODY =
            "Your %s cleared review and is now visible to everyone.";
}
