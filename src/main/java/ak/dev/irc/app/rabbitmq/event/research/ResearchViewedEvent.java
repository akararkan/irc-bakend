package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired after the deduplication check confirms this is a unique view.
 * Consumer (analytics) → persists ResearchView record + increments viewCount.
 */
public record ResearchViewedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID   researchId,
        UUID   userId,       // null for anonymous visitors
        String ipAddress,
        String userAgent
) {
    public static ResearchViewedEvent of(UUID researchId, UUID userId,
                                          String ipAddress, String userAgent) {
        return new ResearchViewedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, userId, ipAddress, userAgent
        );
    }
}
