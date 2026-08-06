package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.common.messages.QnaMessages;
import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAnswerSourceRequest {

    @NotNull(message = QnaMessages.VAL_SOURCE_TYPE_REQUIRED)
    private SourceType sourceType;

    @NotBlank(message = QnaMessages.VAL_SOURCE_TITLE_REQUIRED)
    @Size(max = 500, message = QnaMessages.VAL_SOURCE_TITLE_MAX)
    private String title;

    @Size(max = 5000, message = QnaMessages.VAL_SOURCE_CITATION_MAX)
    private String citationText;

    private String url;

    private String isbn;
}
