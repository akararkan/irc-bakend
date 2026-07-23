package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** A pending/decided join request as shown to channel admins. */
public record JoinRequestResponse(
        UUID id,
        UUID conversationId,
        UUID userId,
        String username,
        String fullName,
        String status,
        LocalDateTime requestedAt,
        UUID decidedBy,
        LocalDateTime decidedAt
) {}
