package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.messages.ResearchMessages;
import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SourceRequest(

    @NotNull(message = ResearchMessages.VAL_SOURCE_TYPE_REQUIRED)
    SourceType sourceType,

    @NotBlank(message = ResearchMessages.VAL_SOURCE_TITLE_REQUIRED)
    @Size(max = 500)
    String title,

    @Size(max = 10000)
    String citationText,

    String url,

    @Size(max = 20)
    String isbn,

    Integer displayOrder
) {}
