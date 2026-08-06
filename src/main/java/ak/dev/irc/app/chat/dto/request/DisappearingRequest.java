package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/** Set the disappearing-messages timer. {@code seconds = 0} turns it off.
 *  Common presets: 86400 (24h), 604800 (7d), 7776000 (90d). */
@Data
public class DisappearingRequest {

    @PositiveOrZero(message = ChatMessages.VAL_SECONDS_NON_NEGATIVE)
    private int seconds;
}
