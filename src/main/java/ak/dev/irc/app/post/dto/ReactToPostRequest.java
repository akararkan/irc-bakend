package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostReactionType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReactToPostRequest {

    @NotNull(message = "Reaction type is required")
    private PostReactionType reactionType;
}
