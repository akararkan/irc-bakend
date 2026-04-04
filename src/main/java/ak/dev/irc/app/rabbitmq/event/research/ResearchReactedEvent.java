package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a user reacts to a research publication (new reaction only, not updates).
 * Consumer → creates a PUBLICATION_LIKED notification for the researcher.
 */
public record ResearchReactedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID   researchId,
        String researchTitle,

        /** The researcher who owns the publication */
        UUID   researcherId,

        /** The user who reacted */
        UUID   actorId,
        String actorUsername,
        String actorFullName,

        /** e.g. LIKE, LOVE, INSIGHTFUL */
        String reactionType
) {
    public static ResearchReactedEvent of(UUID researchId, String researchTitle,
                                           UUID researcherId,
                                           UUID actorId, String actorUsername, String actorFullName,
                                           String reactionType) {
        return new ResearchReactedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, researchTitle,
                researcherId,
                actorId, actorUsername, actorFullName,
                reactionType
        );
    }
}
