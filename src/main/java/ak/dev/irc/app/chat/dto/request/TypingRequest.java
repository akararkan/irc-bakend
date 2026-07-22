package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Ephemeral typing indicator. {@code isTyping=false} is optional — the Redis
 *  TTL auto-clears a stale "typing" if the client simply stops sending. */
@Data
public class TypingRequest {
    private boolean isTyping;
}
