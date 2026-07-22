package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Forward an existing message into another conversation the caller belongs to. */
@Data
public class ForwardMessageRequest {

    @NotNull(message = "targetConversationId is required")
    private UUID targetConversationId;

    /** Idempotency key for the forwarded copy. */
    @NotBlank(message = "clientNonce is required")
    @Size(max = 64)
    private String clientNonce;
}
