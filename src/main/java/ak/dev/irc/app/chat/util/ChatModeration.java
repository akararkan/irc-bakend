package ak.dev.irc.app.chat.util;

import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationOutcome;

import java.util.UUID;

/**
 * Reads and writes the {@code moderation_status} marker the chat surfaces share
 * (messages in Cassandra, conversations and live streams in Postgres).
 *
 * <p>The vocabulary is {@link ModerationStatus}, stored as its name. {@code null}
 * is the important value: it means either "written before automated moderation
 * existed" or "cleared", and both read as approved. Encoding a cleared unit as
 * null rather than {@code "APPROVED"} is what lets the approval write be a cell
 * delete, which matters for messages living under a disappearing-messages TTL —
 * a plain {@code UPDATE … = 'APPROVED'} would leave one never-expiring column
 * behind and resurrect the row as a ghost.</p>
 */
public final class ChatModeration {

    private ChatModeration() {}

    /** Marker to persist for an outcome; {@code null} when the unit may publish now. */
    public static String marker(ModerationOutcome outcome) {
        return outcome == null || outcome.approved() ? null : outcome.status().name();
    }

    /** True while the unit has not cleared — PENDING, IN_REVIEW or REJECTED. */
    public static boolean held(String moderationStatus) {
        return moderationStatus != null
                && !ModerationStatus.APPROVED.name().equals(moderationStatus);
    }

    /**
     * Whether a unit must be withheld from this viewer. Authors always see their
     * own text (§5.1) — hiding it from them too would just look like the platform
     * ate the message.
     */
    public static boolean hiddenFrom(String moderationStatus, UUID authorId, UUID viewerId) {
        return held(moderationStatus)
                && (viewerId == null || authorId == null || !viewerId.equals(authorId));
    }
}
