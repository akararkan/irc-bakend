package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostReactionType;
import lombok.Data;

@Data
public class ReactToPostRequest {

    private PostReactionType reactionType = PostReactionType.LIKE;
}
