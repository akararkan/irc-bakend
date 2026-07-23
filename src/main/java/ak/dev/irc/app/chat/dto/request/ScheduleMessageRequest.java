package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.MessageType;
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

    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be in the future")
    private LocalDateTime scheduledAt;

    @NotBlank(message = "clientNonce is required")
    @Size(max = 64)
    private String clientNonce;

    @NotNull(message = "type is required")
    private MessageType type;

    @Size(max = 8000)
    private String body;

    private Long replyToId;

    @Valid
    @Size(max = 10)
    private List<MediaRefDto> media;
}
