package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** A pending (or resolved) message request in the recipient's Requests inbox. */
public record MessageRequestResponse(
        UUID id,
        UUID conversationId,
        UUID requesterId,
        String requesterUsername,
        String requesterFullName,
        String status,
        long firstMessageId,
        int messageCount,
        LocalDateTime createdAt
) {}
