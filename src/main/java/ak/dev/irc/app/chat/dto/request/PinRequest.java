package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Pin/unpin a conversation in my inbox. */
@Data
public class PinRequest {
    private boolean pinned;
}
