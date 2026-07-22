package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Result of creating/rotating an invite link. The plaintext {@code token} is
 * returned exactly once (only its hash is stored); the caller shares
 * {@code token} in the link and it can never be recovered from the database.
 */
public record InviteLinkResponse(
        UUID conversationId,
        String token,
        LocalDateTime expiresAt,
        Integer maxUses,
        int useCount
) {}
