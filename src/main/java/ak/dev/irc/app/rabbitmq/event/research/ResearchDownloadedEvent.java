package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a user downloads a research file.
 * Consumer (analytics) → persists ResearchDownload record + increments downloadCount.
 *
 * The pre-signed URL is generated synchronously in the HTTP handler before this
 * event is published, so the caller is never blocked waiting for the consumer.
 */
public record ResearchDownloadedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID researchId,
        UUID mediaId,   // null if downloading the research itself, not a specific media file
        UUID userId,    // null for anonymous visitors
        String ipAddress
) {
    public static ResearchDownloadedEvent of(UUID researchId, UUID mediaId,
                                              UUID userId, String ipAddress) {
        return new ResearchDownloadedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, mediaId, userId, ipAddress
        );
    }
}
