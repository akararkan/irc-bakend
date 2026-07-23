package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Cast (or change) a poll vote. Multiple indexes only when the poll allows it. */
@Data
public class PollVoteRequest {

    @NotEmpty(message = "pick at least one option")
    private List<Integer> optionIndexes;
}
