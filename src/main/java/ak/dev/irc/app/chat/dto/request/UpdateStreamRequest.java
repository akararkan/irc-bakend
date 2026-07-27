package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Edit a stream's discovery metadata (owner-only). Both fields are optional — a
 * {@code null} field is left unchanged; a blank {@code title} is rejected by the
 * service (a stream must always have a title). Applies whether the stream is LIVE
 * or ENDED, so an owner can fix a typo mid-broadcast.
 */
@Data
public class UpdateStreamRequest {

    @Size(max = 140)
    private String title;

    @Size(max = 500)
    private String description;
}
