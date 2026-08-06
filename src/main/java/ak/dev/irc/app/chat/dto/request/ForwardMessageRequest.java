package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Forward an existing message into another conversation the caller belongs to. */
@Data
public class ForwardMessageRequest {

    @NotNull(message = ChatMessages.VAL_TARGET_CONVERSATION_REQUIRED)
    private UUID targetConversationId;

    /** Idempotency key for the forwarded copy. */
    @NotBlank(message = ChatMessages.VAL_CLIENT_NONCE_REQUIRED)
    @Size(max = 64)
    private String clientNonce;
}
