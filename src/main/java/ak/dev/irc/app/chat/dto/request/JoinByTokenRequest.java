package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Join a group via an invite-link token. */
@Data
public class JoinByTokenRequest {

    @NotBlank(message = ChatMessages.VAL_TOKEN_REQUIRED)
    private String token;
}
