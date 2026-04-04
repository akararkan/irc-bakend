package ak.dev.irc.app.research.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateMediaRequest(

    @Size(max = 500, message = "Caption must not exceed 500 characters")
    String caption,

    @Size(max = 255, message = "Alt text must not exceed 255 characters")
    String altText,

    Integer displayOrder,
    Integer durationSeconds,
    Integer widthPx,
    Integer heightPx
) {}
