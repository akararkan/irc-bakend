package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAnswerSourceRequest {

    @NotNull(message = "Source type is required")
    private SourceType sourceType;

    @NotBlank(message = "Source title is required")
    @Size(max = 500, message = "Source title must not exceed 500 characters")
    private String title;

    @Size(max = 5000, message = "Citation text must not exceed 5000 characters")
    private String citationText;

    private String url;

    private String doi;

    private String isbn;
}
