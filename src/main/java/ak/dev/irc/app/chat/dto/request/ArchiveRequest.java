package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Archive/unarchive a conversation in my inbox. */
@Data
public class ArchiveRequest {
    private boolean archived;
}
