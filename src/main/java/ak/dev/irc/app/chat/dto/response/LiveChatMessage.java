package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A live-chat line broadcast to a stream's viewers over SSE (ephemeral). */
public record LiveChatMessage(
        UUID streamId,
        UUID userId,
        String username,
        String text,
        Instant sentAt
) {}
