package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A queued (send-later) message in the caller's Scheduled tray. */
public record ScheduledMessageResponse(
        UUID id,
        UUID conversationId,
        String type,
        String body,
        List<MediaRefResponse> media,
        Long replyToId,
        LocalDateTime scheduledAt,
        String status,
        Long sentMessageId,
        LocalDateTime createdAt
) {}
