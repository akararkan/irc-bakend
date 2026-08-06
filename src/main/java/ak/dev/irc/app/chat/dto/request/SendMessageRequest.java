package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Send a message. The {@code clientNonce} is required and reused across retries
 * so a duplicate delivery returns the already-created message instead of a second
 * row (exactly-once effect).
 */
@Data
public class SendMessageRequest {

    /** Idempotency key — a client-generated UUID reused on every retry of THIS message. */
    @NotBlank(message = ChatMessages.VAL_CLIENT_NONCE_IDEMPOTENT)
    @Size(max = 64)
    private String clientNonce;

    @NotNull(message = ChatMessages.VAL_TYPE_REQUIRED)
    private MessageType type;

    /** Text body — null for pure-media messages. */
    @Size(max = 8000, message = ChatMessages.VAL_MESSAGE_MAX)
    private String body;

    /** Snowflake id of the message being replied to (optional). */
    private Long replyToId;

    @Valid
    @Size(max = 10, message = ChatMessages.VAL_ATTACHMENTS_MAX)
    private List<MediaRefDto> media;

    /** Telegram "silent post" — delivered normally but with no push notification. */
    private boolean silent;

    /** Poll payload — required when {@code type} is {@code POLL}, else must be absent. */
    @Valid
    private PollCreateDto poll;

    /** Location payload — required when {@code type} is {@code LOCATION}. */
    @Valid
    private LocationDto location;

    /** Contact payload — required when {@code type} is {@code CONTACT}. */
    @Valid
    private ContactDto contact;
}
