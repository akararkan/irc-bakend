package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata of an existing invite link (the admin's link manager). The plaintext
 * token is NOT recoverable — only its hash is stored — so {@code shareUrl} is
 * only ever returned by the create call.
 */
public record InviteLinkInfoResponse(
        UUID id,
        UUID conversationId,
        UUID createdBy,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        Integer maxUses,
        int useCount,
        boolean revoked,
        boolean requiresApproval,
        /** No expiry and no use limit. */
        boolean permanent
) {}
