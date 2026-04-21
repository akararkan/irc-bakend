package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAnswerRequest {

    @NotBlank(message = "Answer body is required")
    @Size(max = 10000, message = "Answer must not exceed 10000 characters")
    private String body;
}