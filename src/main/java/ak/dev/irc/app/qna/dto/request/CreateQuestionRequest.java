package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateQuestionRequest {

    @NotBlank(message = "Question title is required")
    @Size(max = 500, message = "Question title must not exceed 500 characters")
    private String title;

    @NotBlank(message = "Question body is required")
    @Size(max = 10000, message = "Question body must not exceed 10000 characters")
    private String body;

    /**
     * Topic tags (e.g. ["hajj", "ramadan"]). Normalized to lowercase, trimmed,
     * deduplicated, capped at 30. Drive trending + tag feeds. Optional.
     */
    @Size(max = 30, message = "A question can have at most 30 tags")
    private List<String> tags;

    /** Free-text keywords for search discoverability. Optional. */
    @Size(max = 2000, message = "Keywords must not exceed 2000 characters")
    private String keywords;

    /** Lock answers from the start (default false). */
    private boolean answersLocked = false;

    /** Maximum number of answers. Null = unlimited. */
    private Integer maxAnswers;
}