package ak.dev.irc.app.qna.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class EditQuestionRequest {

    @JsonAlias({"name", "questionTitle"})
    @Size(max = 500, message = "Question title must not exceed 500 characters")
    private String title;

    /**
     * Question body. Accepts {@code body}, {@code text}, {@code content} or
     * {@code description} as JSON keys so a frontend naming mismatch doesn't
     * silently drop the update (was a recurring foot-gun on posts).
     */
    @JsonAlias({"text", "content", "description"})
    @Size(max = 10000, message = "Question body must not exceed 10000 characters")
    private String body;

    private Boolean answersLocked;

    private Integer maxAnswers;

    /**
     * Replacement tag set. {@code null} leaves tags unchanged; an empty list
     * clears them. When present, the Cassandra tag index is fully rebuilt.
     */
    @Size(max = 30, message = "A question can have at most 30 tags")
    private List<String> tags;

    /** {@code null} leaves keywords unchanged. */
    @Size(max = 2000, message = "Keywords must not exceed 2000 characters")
    private String keywords;
}