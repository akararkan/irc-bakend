package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.research.enums.MediaType;

import java.util.UUID;

public record MediaResponse(
    UUID id,
    String fileUrl,
    String originalFileName,
    String mimeType,
    MediaType mediaType,
    Long fileSize,
    Integer displayOrder,
    String caption,
    String altText,
    Integer durationSeconds,
    String thumbnailUrl,
    Integer widthPx,
    Integer heightPx
) {}
