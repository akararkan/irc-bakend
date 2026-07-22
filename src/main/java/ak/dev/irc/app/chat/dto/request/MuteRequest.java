package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

/** Mute a conversation until the given time, or {@code null} to unmute. */
@Data
public class MuteRequest {
    private LocalDateTime mutedUntil;
}
