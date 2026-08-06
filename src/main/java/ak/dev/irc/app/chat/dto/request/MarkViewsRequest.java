package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Batch view marker — the client reports the channel posts that entered the
 *  viewport. Each user is counted once per post (server-side dedupe). */
@Data
public class MarkViewsRequest {

    @NotEmpty
    @Size(max = 100, message = ChatMessages.VAL_VIEWS_BATCH_MAX)
    private List<Long> messageIds;
}
