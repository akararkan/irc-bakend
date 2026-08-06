package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Transfer group ownership to another current member (owner only). */
@Data
public class TransferOwnerRequest {

    @NotNull(message = ChatMessages.VAL_NEW_OWNER_REQUIRED)
    private UUID newOwnerId;
}
