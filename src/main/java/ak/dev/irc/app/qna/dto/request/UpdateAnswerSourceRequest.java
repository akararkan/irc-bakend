package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAnswerSourceRequest {

    private SourceType sourceType;

    @Size(max = 500, message = "Source title must not exceed 500 characters")
    private String title;

    @Size(max = 5000, message = "Citation text must not exceed 5000 characters")
    private String citationText;

    private String url;

    @Size(max = 255, message = "DOI must not exceed 255 characters")
    private String doi;

    @Size(max = 20, message = "ISBN must not exceed 20 characters")
    private String isbn;

    private Integer displayOrder;
}
