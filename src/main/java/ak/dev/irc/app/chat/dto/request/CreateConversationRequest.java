package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.ConversationType;
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

    @NotNull(message = "type is required (DIRECT | GROUP)")
    private ConversationType type;

    /** DIRECT only. */
    private UUID recipientId;

    /** GROUP only. */
    @Size(max = 120, message = "group title must not exceed 120 characters")
    private String title;

    /** GROUP only — optional description/topic. */
    @Size(max = 500, message = "group description must not exceed 500 characters")
    private String description;

    /** GROUP only — R2/S3 key for the avatar. */
    @Size(max = 255)
    private String avatarKey;

    /** GROUP only — initial members (excluding the creator). */
    @Size(max = 256, message = "a group can start with at most 256 members")
    private List<UUID> memberIds;
}
