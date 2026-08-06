package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.common.messages.QnaMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateQuestionRequest {

    @NotBlank(message = QnaMessages.VAL_QUESTION_TITLE_REQUIRED)
    @Size(max = 500, message = QnaMessages.VAL_QUESTION_TITLE_MAX)
    private String title;

    @NotBlank(message = QnaMessages.VAL_QUESTION_BODY_REQUIRED)
    @Size(max = 10000, message = QnaMessages.VAL_QUESTION_BODY_MAX)
    private String body;

    /**
     * Topic tags (e.g. ["hajj", "ramadan"]). Normalized to lowercase, trimmed,
     * deduplicated, capped at 30. Drive trending + tag feeds. Optional.
     */
    @Size(max = 30, message = QnaMessages.VAL_QUESTION_TAGS_MAX)
    private List<String> tags;

    /** Free-text keywords for search discoverability. Optional. */
    @Size(max = 2000, message = QnaMessages.VAL_QUESTION_KEYWORDS_MAX)
    private String keywords;

    /** Lock answers from the start (default false). */
    private boolean answersLocked = false;

    /** Maximum number of answers. Null = unlimited. */
    private Integer maxAnswers;
}