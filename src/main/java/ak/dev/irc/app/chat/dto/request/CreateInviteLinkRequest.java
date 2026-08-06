package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Create/rotate a group invite link. Both fields optional (null = no expiry / unlimited uses). */
@Data
public class CreateInviteLinkRequest {

    @Positive(message = ChatMessages.VAL_EXPIRES_IN_HOURS_POSITIVE)
    private Integer expiresInHours;

    @Positive(message = ChatMessages.VAL_MAX_USES_POSITIVE)
    private Integer maxUses;

    /** When true, using the link files a join request an admin must approve. */
    private boolean requiresApproval;
}
