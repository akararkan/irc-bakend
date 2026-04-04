package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.research.enums.SourceType;

import java.util.UUID;

public record SourceResponse(
    UUID id,
    SourceType sourceType,
    String title,
    String citationText,
    String url,
    String doi,
    String isbn,
    String fileUrl,
    String originalFileName,
    String mimeType,
    Long fileSize,
    Integer displayOrder
) {}
