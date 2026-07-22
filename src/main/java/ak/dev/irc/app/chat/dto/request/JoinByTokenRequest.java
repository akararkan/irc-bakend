package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Join a group via an invite-link token. */
@Data
public class JoinByTokenRequest {

    @NotBlank(message = "token is required")
    private String token;
}
