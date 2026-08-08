package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;

/**
 * Applies a deferred verdict to the actual content row.
 *
 * <p>Only the <em>deferred</em> path uses this. When the inline scoring attempt
 * clears content inside the request (the overwhelmingly common case), the
 * content service publishes normally and no applier runs. An applier exists for
 * the minority of units that were written held — because the model was slow,
 * borderline, or down — and whose verdict lands later from the queue worker, the
 * SLA sweeper, or an admin's decision in the review queue.</p>
 *
 * <p>Implementations inject content <em>repositories</em> and publication
 * services, never the content service that submits to moderation, so there is no
 * bean cycle. Where a callback into a service is unavoidable (research
 * publication), it goes through an {@code ObjectProvider} resolved at call
 * time.</p>
 *
 * <p>Every method must be idempotent. The sweeper re-drives cases whose apply
 * failed, so "approve an already-approved post" has to be a no-op rather than a
 * second fan-out to 50,000 followers.</p>
 */
public interface ModerationApplier {

    ModeratedEntityType supports();

    /**
     * Publish: make the content visible and run whatever publication side effects
     * were skipped at create time (fan-out, search indexing, tag extraction,
     * notifications).
     */
    void onApproved(ModerationCase moderationCase);

    /** Block: keep it hidden from everyone but its author, and mark it removed. */
    void onRejected(ModerationCase moderationCase);

    /**
     * Route to a human: the content stays exactly as held. Default no-op because
     * for most surfaces "held" is already the on-disk state and nothing needs to
     * change — only the queue's view of it does.
     */
    default void onInReview(ModerationCase moderationCase) {
        // no-op: the content is already in its held representation
    }
}
