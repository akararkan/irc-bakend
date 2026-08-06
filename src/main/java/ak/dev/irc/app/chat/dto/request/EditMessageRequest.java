package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Edit the text body of one's own message. */
@Data
public class EditMessageRequest {

    @NotBlank(message = ChatMessages.VAL_BODY_REQUIRED)
    @Size(max = 8000, message = ChatMessages.VAL_MESSAGE_MAX)
    private String body;
}
