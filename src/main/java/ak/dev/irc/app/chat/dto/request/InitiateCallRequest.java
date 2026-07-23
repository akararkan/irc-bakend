package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.CallType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Start a call in a conversation. */
@Data
public class InitiateCallRequest {
    @NotNull
    private CallType type;   // VOICE | VIDEO
}
