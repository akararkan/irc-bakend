package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.messages.ResearchMessages;
import jakarta.validation.constraints.Size;

public record UpdateMediaRequest(

    @Size(max = 500, message = ResearchMessages.VAL_CAPTION_MAX_500)
    String caption,

    @Size(max = 255, message = ResearchMessages.VAL_ALT_TEXT_MAX_255)
    String altText,

    Integer displayOrder,
    Integer durationSeconds,
    Integer widthPx,
    Integer heightPx
) {}
