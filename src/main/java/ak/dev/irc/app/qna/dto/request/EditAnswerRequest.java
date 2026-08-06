package ak.dev.irc.app.qna.dto.request;


import ak.dev.irc.app.common.messages.QnaMessages;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditAnswerRequest {

    /** Answer body — see {@link EditQuestionRequest#getBody()} for the alias rationale. */
    @JsonAlias({"text", "content", "description", "answer", "answerBody"})
    @NotBlank(message = QnaMessages.VAL_ANSWER_BODY_REQUIRED)
    @Size(max = 5000, message = QnaMessages.VAL_ANSWER_MAX_EDIT)
    private String body;
}