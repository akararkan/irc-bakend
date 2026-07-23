package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/** One participant's state within a call. */
public record CallParticipantResponse(
        UUID userId,
        String state,      // INVITED | JOINED | DECLINED | LEFT
        Instant joinedAt,
        Instant leftAt
) {}
