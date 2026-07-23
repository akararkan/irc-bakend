package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.chat.dto.request.MediaRefDto;

import java.time.LocalDateTime;
import java.util.List;

/** The caller's saved draft for one conversation. */
public record DraftResponse(
        java.util.UUID conversationId,
        String body,
        List<MediaRefDto> media,
        Long replyToId,
        LocalDateTime updatedAt
) {}
