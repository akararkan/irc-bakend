package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Save/overwrite the caller's draft for a conversation. */
@Data
public class SaveDraftRequest {

    @Size(max = 8000)
    private String body;

    @Valid
    @Size(max = 10)
    private List<MediaRefDto> media;

    private Long replyToId;
}
