package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.ReactionType;
import jakarta.validation.constraints.NotNull;

public record ReactRequest(

    @NotNull(message = "Reaction type is required")
    ReactionType reactionType
) {}
