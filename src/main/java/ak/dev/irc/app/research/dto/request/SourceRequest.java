package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SourceRequest(

    @NotNull(message = "Source type is required")
    SourceType sourceType,

    @NotBlank(message = "Source title is required")
    @Size(max = 500)
    String title,

    @Size(max = 10000)
    String citationText,

    String url,

    @Size(max = 255)
    String doi,

    @Size(max = 20)
    String isbn,

    Integer displayOrder
) {}
