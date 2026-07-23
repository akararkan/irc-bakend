package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Post a story as the channel (JSON path; multipart uploads use params). */
@Data
public class CreateChannelStoryRequest {

    /** TEXT | IMAGE | VIDEO | LINKED_POST | … (defaults to TEXT/IMAGE by content). */
    @Size(max = 20)
    private String storyType;

    @Size(max = 2000)
    private String textContent;

    /** Pre-uploaded media URL (or use the multipart endpoint). */
    @Size(max = 500)
    private String mediaUrl;

    @Size(max = 500)
    private String thumbnailUrl;

    /** 8 / 16 / 24 (hours); defaults to 24. */
    private Integer lifetimeHours;
}
