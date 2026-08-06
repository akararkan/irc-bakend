package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Add or change a reaction on a message. */
@Data
public class ReactRequest {

    @NotBlank(message = ChatMessages.VAL_EMOJI_REQUIRED)
    @Size(max = 16, message = ChatMessages.VAL_EMOJI_SINGLE_GRAPHEME)
    private String emoji;
}
