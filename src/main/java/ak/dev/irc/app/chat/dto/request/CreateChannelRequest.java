package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Create a broadcast channel. A public channel requires a {@code handle}. */
@Data
public class CreateChannelRequest {

    @NotBlank
    @Size(max = 120)
    private String title;

    @Size(max = 500)
    private String description;

    /** Public @handle (a–z, 0–9, underscore; 3–32 chars). Required if public. */
    @Size(max = 32)
    private String handle;

    /** Public channels are discoverable and self-subscribable; private ones join by invite. */
    private boolean publicChannel = true;

    /** Directory category slug (e.g. "news", "science"). */
    @Size(max = 48)
    private String category;

    /** Initial channel settings; defaults when omitted. */
    private ak.dev.irc.app.chat.dto.ChannelSettings settings;
}
