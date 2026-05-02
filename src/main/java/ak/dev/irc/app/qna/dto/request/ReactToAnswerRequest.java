package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.qna.enums.AnswerReactionType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReactToAnswerRequest {

    @NotNull(message = "Reaction type is required")
    private AnswerReactionType reactionType;
}
