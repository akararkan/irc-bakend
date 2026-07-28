package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A single ephemeral "tap" reaction floating up over a live stream, broadcast to
 * everyone watching. Never stored — it is an animation, not a counter (see
 * {@link ak.dev.irc.app.chat.enums.StreamReactionType}).
 */
public record StreamReaction(
        UUID streamId,
        UUID userId,
        /** {@code StreamReactionType} name, e.g. {@code LIKE}. */
        String type,
        Instant sentAt
) {}
