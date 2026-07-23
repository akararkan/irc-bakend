package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A live-chat line sent during a stream. */
@Data
public class LiveChatRequest {
    @NotBlank
    @Size(max = 500)
    private String text;
}
