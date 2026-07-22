package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.MessageType;
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
    @NotBlank(message = "clientNonce is required for idempotent send")
    @Size(max = 64)
    private String clientNonce;

    @NotNull(message = "type is required")
    private MessageType type;

    /** Text body — null for pure-media messages. */
    @Size(max = 8000, message = "a message may not exceed 8000 characters")
    private String body;

    /** Snowflake id of the message being replied to (optional). */
    private Long replyToId;

    @Valid
    @Size(max = 10, message = "at most 10 attachments per message")
    private List<MediaRefDto> media;
}
