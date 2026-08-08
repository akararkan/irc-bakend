package ak.dev.irc.app.moderation.enums;

/**
 * The content-unit state machine of MODERATION_ROADMAP.md §5.1.
 *
 * <pre>
 *   [*] --> PENDING     user submits
 *   PENDING --> APPROVED    all text cleared inside the hold window
 *   PENDING --> REJECTED    a label crossed its high threshold, or a hard blocklist hit
 *   PENDING --> IN_REVIEW   uncertain band, or the hold window expired with no verdict
 *   IN_REVIEW --> APPROVED | REJECTED   admin decides
 *   APPROVED --> PENDING    edited (re-enters moderation)
 * </pre>
 *
 * <p>Only {@link #APPROVED} is visible to anyone other than the author. The
 * author always sees their own content, labelled with its state — hiding it
 * from them too would just look like the platform ate their post.</p>
 */
public enum ModerationStatus {

    /** Held. Written, not published; a verdict is still expected. */
    PENDING,

    /** Cleared. The one state that makes content visible to other users. */
    APPROVED,

    /** Blocked. Visible to its author (marked as removed) and to moderators only. */
    REJECTED,

    /** Waiting on a human. Same visibility as {@link #PENDING}, but it is in the queue. */
    IN_REVIEW;

    /** True when no further automated transition is expected. */
    public boolean terminal() {
        return this == APPROVED || this == REJECTED;
    }

    /** True when the content must stay hidden from everyone but its author. */
    public boolean held() {
        return this != APPROVED;
    }
}
