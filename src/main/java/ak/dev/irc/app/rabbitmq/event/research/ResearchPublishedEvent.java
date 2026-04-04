package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a researcher publishes their research.
 * Consumer → fans out a PUBLICATION_LIKED notification to all followers of the researcher.
 */
public record ResearchPublishedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID   researchId,
        String researchTitle,
        String researchSlug,

        UUID   researcherId,
        String researcherUsername,
        String researcherFullName
) {
    public static ResearchPublishedEvent of(UUID researchId, String title, String slug,
                                             UUID researcherId, String username, String fullName) {
        return new ResearchPublishedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, title, slug,
                researcherId, username, fullName
        );
    }
}
