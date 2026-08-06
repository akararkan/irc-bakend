package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Partial update of a group's title / avatar / settings. Null fields are left unchanged. */
@Data
public class UpdateConversationRequest {

    @Size(max = 120, message = ChatMessages.VAL_GROUP_TITLE_MAX)
    private String title;

    @Size(max = 500, message = ChatMessages.VAL_GROUP_DESCRIPTION_MAX)
    private String description;

    @Size(max = 255)
    private String avatarKey;

    private GroupSettings settings;
}
