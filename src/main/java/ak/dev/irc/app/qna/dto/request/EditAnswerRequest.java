package ak.dev.irc.app.qna.dto.request;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditAnswerRequest {

    @NotBlank(message = "Answer body is required")
    @Size(max = 5000, message = "Answer must not exceed 5000 characters")
    private String body;
}