package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Transfer group ownership to another current member (owner only). */
@Data
public class TransferOwnerRequest {

    @NotNull(message = "newOwnerId is required")
    private UUID newOwnerId;
}
