package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a user reacts to a comment on a research publication.
 *
 * <p>Consumer responsibilities:
 * <ul>
 *   <li>Dispatch a {@code RESEARCH_COMMENT_REACTED} notification to the
 *       comment author (suppressed when the actor is the comment author).</li>
 *   <li>Record a {@code RESEARCH_COMMENT_REACTION} user-activity row.</li>
 * </ul>
 * </p>
 *
 * <p>Mirrors {@code PostCommentReactedEvent} on the post side.</p>
 */
public record ResearchCommentReactedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID   researchId,
        String researchTitle,
        UUID   commentId,

        /** The user who wrote the comment (notification target). */
        UUID   commentAuthorId,

        /** The user who reacted. */
        UUID   reactorId,
        String reactorUsername,
        String reactorFullName,

        /** Always {@code LIKE} in the current single-reaction model. */
        String reactionType
) {
    public static ResearchCommentReactedEvent of(UUID researchId, String researchTitle,
                                                  UUID commentId, UUID commentAuthorId,
                                                  UUID reactorId, String reactorUsername, String reactorFullName,
                                                  String reactionType) {
        return new ResearchCommentReactedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, researchTitle,
                commentId, commentAuthorId,
                reactorId, reactorUsername, reactorFullName,
                reactionType
        );
    }
}
