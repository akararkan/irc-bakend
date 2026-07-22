package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Restrict (read-only/mute) or un-restrict a group member. */
@Data
public class RestrictMemberRequest {
    private boolean restricted;
}
