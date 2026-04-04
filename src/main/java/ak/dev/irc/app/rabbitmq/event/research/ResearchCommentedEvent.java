package ak.dev.irc.app.rabbitmq.event.research;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fired when a user adds a comment to a research publication.
 * Consumer → creates a PUBLICATION_COMMENTED notification for the researcher.
 */
public record ResearchCommentedEvent(
        String        eventId,
        LocalDateTime occurredAt,

        UUID   researchId,
        String researchTitle,

        /** The researcher who owns the publication */
        UUID   researcherId,

        /** The user who wrote the comment */
        UUID   actorId,
        String actorUsername,
        String actorFullName,

        UUID   commentId,

        /** First 120 chars of the comment body, for notification preview */
        String commentPreview
) {
    public static ResearchCommentedEvent of(UUID researchId, String researchTitle,
                                             UUID researcherId,
                                             UUID actorId, String actorUsername, String actorFullName,
                                             UUID commentId, String fullCommentContent) {
        String preview = fullCommentContent != null && fullCommentContent.length() > 120
                ? fullCommentContent.substring(0, 120) + "…"
                : fullCommentContent;

        return new ResearchCommentedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                researchId, researchTitle,
                researcherId,
                actorId, actorUsername, actorFullName,
                commentId, preview
        );
    }
}
