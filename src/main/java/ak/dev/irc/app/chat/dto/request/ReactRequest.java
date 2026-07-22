package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Add or change a reaction on a message. */
@Data
public class ReactRequest {

    @NotBlank(message = "emoji is required")
    @Size(max = 16, message = "emoji must be a single grapheme")
    private String emoji;
}
