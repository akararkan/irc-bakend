package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A single message rendered for the client. */
public record MessageResponse(
        long messageId,
        UUID conversationId,
        UUID senderId,
        String senderUsername,
        String senderFullName,
        String type,
        String body,
        List<MediaRefResponse> media,
        Long replyToId,
        ReplyPreview replyTo,
        UUID forwardedFrom,
        Set<UUID> mentions,
        List<ReactionSummary> reactions,
        Instant editedAt,
        boolean deleted,
        String systemEvent,
        Instant createdAt
) {}
