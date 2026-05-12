package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.ReactionType;

public record ReactRequest(
    ReactionType reactionType
) {
    public ReactRequest {
        if (reactionType == null) reactionType = ReactionType.LIKE;
    }

    public ReactRequest() {
        this(ReactionType.LIKE);
    }
}
