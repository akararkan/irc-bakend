package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.moderation.enums.ModerationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the pipeline decided about one submission, returned synchronously to the
 * content service that submitted it.
 *
 * <p>Callers act on exactly three questions: may I publish this now
 * ({@link #approved()}), must I refuse it ({@link #rejected()}), or should I
 * persist it held and let the applier finish later ({@link #held()}).</p>
 *
 * @param status       terminal or held state of the unit
 * @param caseId       the {@code moderation_cases} row, null when moderation is
 *                     off for this entity type and nothing was recorded
 * @param topLabel     which label drove the verdict
 * @param topScore     that label's probability
 * @param blocklistHit deny-list term that matched, if any
 * @param reasonCode   {@code BLOCKLIST}, {@code MODEL}, {@code SLA_BREACH},
 *                     {@code DISABLED}, {@code NO_TEXT}, {@code ADMIN}
 * @param holdDeadline when the SLA sweeper will force a fallback verdict
 */
public record ModerationOutcome(ModerationStatus status,
                                UUID caseId,
                                String topLabel,
                                double topScore,
                                String blocklistHit,
                                String reasonCode,
                                LocalDateTime holdDeadline) {

    public static final String REASON_BLOCKLIST = "BLOCKLIST";
    public static final String REASON_MODEL = "MODEL";
    public static final String REASON_SLA_BREACH = "SLA_BREACH";
    public static final String REASON_DISABLED = "DISABLED";
    public static final String REASON_NO_TEXT = "NO_TEXT";
    public static final String REASON_ADMIN = "ADMIN";
    public static final String REASON_INFERENCE_DOWN = "INFERENCE_UNAVAILABLE";

    /** Moderation is off for this type, or the submission carried no text at all. */
    public static ModerationOutcome passthrough(String reasonCode) {
        return new ModerationOutcome(ModerationStatus.APPROVED, null, null, 0, null, reasonCode, null);
    }

    public boolean approved() {
        return status == ModerationStatus.APPROVED;
    }

    public boolean rejected() {
        return status == ModerationStatus.REJECTED;
    }

    /** Written but not published: PENDING (verdict still coming) or IN_REVIEW (waiting on a human). */
    public boolean held() {
        return status == ModerationStatus.PENDING || status == ModerationStatus.IN_REVIEW;
    }

    /**
     * The {@code posts_by_id.status} string for Cassandra surfaces that already
     * gate on a raw status column. {@code PENDING_REVIEW} and {@code REJECTED}
     * are both already in {@code GlobalSearchService.DEAD_STATUSES}, so search
     * hides them without any further wiring.
     */
    public String postStatus() {
        return switch (status) {
            case APPROVED -> "PUBLISHED";
            case REJECTED -> "REJECTED";
            case PENDING, IN_REVIEW -> "PENDING_REVIEW";
        };
    }
}
