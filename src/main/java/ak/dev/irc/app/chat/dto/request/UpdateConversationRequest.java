package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.dto.GroupSettings;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Partial update of a group's title / avatar / settings. Null fields are left unchanged. */
@Data
public class UpdateConversationRequest {

    @Size(max = 120, message = "group title must not exceed 120 characters")
    private String title;

    @Size(max = 500, message = "group description must not exceed 500 characters")
    private String description;

    @Size(max = 255)
    private String avatarKey;

    private GroupSettings settings;
}
