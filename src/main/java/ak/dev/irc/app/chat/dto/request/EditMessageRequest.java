package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Edit the text body of one's own message. */
@Data
public class EditMessageRequest {

    @NotBlank(message = "body is required")
    @Size(max = 8000, message = "a message may not exceed 8000 characters")
    private String body;
}
