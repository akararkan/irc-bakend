package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.qna.enums.AnswerReactionType;
import lombok.Data;

@Data
public class ReactToAnswerRequest {

    private AnswerReactionType reactionType = AnswerReactionType.LIKE;
}
