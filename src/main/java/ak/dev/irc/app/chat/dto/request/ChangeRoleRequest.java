package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Promote/demote a member ({@code ADMIN} or {@code MEMBER}). */
@Data
public class ChangeRoleRequest {

    @NotNull(message = ChatMessages.VAL_ROLE_REQUIRED)
    private MemberRole role;
}
