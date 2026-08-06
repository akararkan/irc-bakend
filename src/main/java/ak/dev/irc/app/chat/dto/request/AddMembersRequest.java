package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Add one or more members to a group. */
@Data
public class AddMembersRequest {

    @NotEmpty(message = ChatMessages.VAL_USER_IDS_REQUIRED)
    @Size(max = 100, message = ChatMessages.VAL_ADD_MEMBERS_MAX)
    private List<UUID> userIds;
}
