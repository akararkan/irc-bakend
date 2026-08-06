package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Cast (or change) a poll vote. Multiple indexes only when the poll allows it. */
@Data
public class PollVoteRequest {

    @NotEmpty(message = ChatMessages.VAL_POLL_VOTE_PICK_ONE)
    private List<Integer> optionIndexes;
}
