package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.common.messages.QnaMessages;
import ak.dev.irc.app.research.enums.SourceType;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAnswerSourceRequest {

    private SourceType sourceType;

    @Size(max = 500, message = QnaMessages.VAL_SOURCE_TITLE_MAX)
    private String title;

    @Size(max = 5000, message = QnaMessages.VAL_SOURCE_CITATION_MAX)
    private String citationText;

    private String url;

    @Size(max = 20, message = QnaMessages.VAL_SOURCE_ISBN_MAX)
    private String isbn;

    private Integer displayOrder;
}
