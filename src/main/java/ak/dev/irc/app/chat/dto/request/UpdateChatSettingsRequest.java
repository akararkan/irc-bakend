package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Partial update of chat privacy settings — null fields are left unchanged. */
@Data
public class UpdateChatSettingsRequest {
    private Boolean readReceiptsEnabled;
    private Boolean lastSeenVisible;
    private Boolean typingIndicatorsEnabled;
}
