package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Go live. */
@Data
public class StartStreamRequest {
    @NotBlank
    @Size(max = 140)
    private String title;

    @Size(max = 500)
    private String description;

    /** Record this broadcast to disk so the host can download it afterwards.
     *  Optional; defaults to {@code false} (opt-in) when absent. */
    private Boolean record;
}
