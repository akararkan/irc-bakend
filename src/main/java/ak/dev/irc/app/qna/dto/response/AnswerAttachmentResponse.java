package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.research.enums.MediaType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerAttachmentResponse(
        UUID id,
        UUID answerId,
        String fileUrl,
        String originalFileName,
        String mimeType,
        MediaType mediaType,
        Long fileSize,
        Integer displayOrder,
        String caption,
        Integer durationSeconds,
        String thumbnailUrl,
        LocalDateTime createdAt,
        /** Client variant map (thumb/feed/full/v720…); empty for legacy uploads. */
        java.util.Map<String, String> variants,
        /** True while a video's rendition ladder is still being produced. */
        Boolean processing
) {}
