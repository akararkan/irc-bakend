package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Create a DIRECT (get-or-create) or GROUP conversation.
 * <ul>
 *   <li>DIRECT — {@code recipientId} required; {@code title}/{@code memberIds} ignored.</li>
 *   <li>GROUP  — {@code title} required; {@code memberIds} are the initial members.</li>
 * </ul>
 */
@Data
public class CreateConversationRequest {

    @NotNull(message = ChatMessages.VAL_CONVERSATION_TYPE_REQUIRED)
    private ConversationType type;

    /** DIRECT only. */
    private UUID recipientId;

    /** GROUP only. */
    @Size(max = 120, message = ChatMessages.VAL_GROUP_TITLE_MAX)
    private String title;

    /** GROUP only — optional description/topic. */
    @Size(max = 500, message = ChatMessages.VAL_GROUP_DESCRIPTION_MAX)
    private String description;

    /** GROUP only — R2/S3 key for the avatar. */
    @Size(max = 255)
    private String avatarKey;

    /** GROUP only — initial members (excluding the creator). */
    @Size(max = 256, message = ChatMessages.VAL_GROUP_MEMBERS_MAX)
    private List<UUID> memberIds;
}
