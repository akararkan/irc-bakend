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
    Integer heightPx,
    /** Client variant map (thumb/feed/full/v720…); empty for legacy uploads. */
    java.util.Map<String, String> variants,
    /** True while a video's rendition ladder is still being produced. */
    Boolean processing
) {}
