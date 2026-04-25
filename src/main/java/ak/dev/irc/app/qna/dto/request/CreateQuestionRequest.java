package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateQuestionRequest {

    @NotBlank(message = "Question title is required")
    @Size(max = 500, message = "Question title must not exceed 500 characters")
    private String title;

    @NotBlank(message = "Question body is required")
    @Size(max = 10000, message = "Question body must not exceed 10000 characters")
    private String body;

    /** Lock answers from the start (default false). */
    private boolean answersLocked = false;

    /** Maximum number of answers. Null = unlimited. */
    private Integer maxAnswers;
}