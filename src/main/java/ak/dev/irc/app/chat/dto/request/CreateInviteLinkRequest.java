package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Create/rotate a group invite link. Both fields optional (null = no expiry / unlimited uses). */
@Data
public class CreateInviteLinkRequest {

    @Positive(message = "expiresInHours must be positive")
    private Integer expiresInHours;

    @Positive(message = "maxUses must be positive")
    private Integer maxUses;

    /** When true, using the link files a join request an admin must approve. */
    private boolean requiresApproval;
}
