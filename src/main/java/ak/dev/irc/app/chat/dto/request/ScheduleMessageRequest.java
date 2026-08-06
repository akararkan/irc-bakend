package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Queue a message to be sent at a future time (Telegram "Schedule message"). */
@Data
public class ScheduleMessageRequest {

    @NotNull(message = ChatMessages.VAL_SCHEDULED_AT_REQUIRED)
    @Future(message = ChatMessages.VAL_SCHEDULED_AT_FUTURE)
    private LocalDateTime scheduledAt;

    @NotBlank(message = ChatMessages.VAL_CLIENT_NONCE_REQUIRED)
    @Size(max = 64)
    private String clientNonce;

    @NotNull(message = ChatMessages.VAL_TYPE_REQUIRED)
    private MessageType type;

    @Size(max = 8000)
    private String body;

    private Long replyToId;

    @Valid
    @Size(max = 10)
    private List<MediaRefDto> media;

    /** Send silently (no push notification) when the schedule fires. */
    private boolean silent;
}
