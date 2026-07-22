package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Advance the read marker to (and including) the given Snowflake message id. */
@Data
public class ReadMarkerRequest {

    @NotNull(message = "lastReadMessageId is required")
    private Long lastReadMessageId;
}
