package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.dto.ChannelSettings;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Partial update of a channel's info/settings. Null fields are left unchanged. */
@Data
public class UpdateChannelRequest {

    @Size(max = 120, message = "channel title must not exceed 120 characters")
    private String title;

    @Size(max = 500, message = "channel description must not exceed 500 characters")
    private String description;

    /** New public @handle. Ignored unless the channel is (or becomes) public. */
    @Size(max = 32)
    private String handle;

    /** Toggle public/private. Turning a channel private clears its @handle. */
    private Boolean publicChannel;

    /** Directory category slug (e.g. "news"). Empty string clears it. */
    @Size(max = 48)
    private String category;

    /** Full replacement of the channel settings blob (send the whole object). */
    private ChannelSettings settings;
}
