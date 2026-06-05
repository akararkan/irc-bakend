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
        String researcherFullName,

        /**
         * Carried for the home-feed fanout (see
         * {@code FeedTimelineService.fanoutResearchPublished}). Lets the
         * consumer mix research into the timeline without an extra DB
         * round-trip per follower. Null on legacy events — the consumer
         * treats null as PUBLIC for back-compat.
         */
        String visibility,
        /** Snapshot URL for the feed card cover image. Null when unset. */
        String coverImageUrl
) {
    public static ResearchPublishedEvent of(UUID researchId, String title, String slug,
                                             UUID researcherId, String username, String fullName) {
        return of(researchId, title, slug, researcherId, username, fullName, null, null);
    }

    public static ResearchPublishedEvent of(UUID researchId, String title, String slug,
                                             UUID researcherId, String username, String fullName,
                                             String visibility, String coverImageUrl) {
        return new ResearchPublishedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, title, slug,
                researcherId, username, fullName,
                visibility, coverImageUrl
        );
    }
}
