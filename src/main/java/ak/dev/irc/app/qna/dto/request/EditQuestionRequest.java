package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.common.messages.QnaMessages;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class EditQuestionRequest {

    @JsonAlias({"name", "questionTitle"})
    @Size(max = 500, message = QnaMessages.VAL_QUESTION_TITLE_MAX)
    private String title;

    /**
     * Question body. Accepts {@code body}, {@code text}, {@code content} or
     * {@code description} as JSON keys so a frontend naming mismatch doesn't
     * silently drop the update (was a recurring foot-gun on posts).
     */
    @JsonAlias({"text", "content", "description"})
    @Size(max = 10000, message = QnaMessages.VAL_QUESTION_BODY_MAX)
    private String body;

    private Boolean answersLocked;

    private Integer maxAnswers;

    /**
     * Replacement tag set. {@code null} leaves tags unchanged; an empty list
     * clears them. When present, the Cassandra tag index is fully rebuilt.
     */
    @Size(max = 30, message = QnaMessages.VAL_QUESTION_TAGS_MAX)
    private List<String> tags;

    /** {@code null} leaves keywords unchanged. */
    @Size(max = 2000, message = QnaMessages.VAL_QUESTION_KEYWORDS_MAX)
    private String keywords;
}