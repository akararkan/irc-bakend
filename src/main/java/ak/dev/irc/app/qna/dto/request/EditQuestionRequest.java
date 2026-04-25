package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditQuestionRequest {

    @Size(max = 500, message = "Question title must not exceed 500 characters")
    private String title;

    @Size(max = 10000, message = "Question body must not exceed 10000 characters")
    private String body;

    private Boolean answersLocked;

    private Integer maxAnswers;
}