package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Advance the read marker to (and including) the given Snowflake message id. */
@Data
public class ReadMarkerRequest {

    @NotNull(message = ChatMessages.VAL_LAST_READ_REQUIRED)
    private Long lastReadMessageId;
}
