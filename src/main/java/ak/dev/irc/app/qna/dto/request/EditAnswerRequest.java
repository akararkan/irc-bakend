package ak.dev.irc.app.qna.dto.request;


import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditAnswerRequest {

    /** Answer body — see {@link EditQuestionRequest#getBody()} for the alias rationale. */
    @JsonAlias({"text", "content", "description", "answer", "answerBody"})
    @NotBlank(message = "Answer body is required")
    @Size(max = 5000, message = "Answer must not exceed 5000 characters")
    private String body;
}