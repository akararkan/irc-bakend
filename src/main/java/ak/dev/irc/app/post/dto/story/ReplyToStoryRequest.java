package ak.dev.irc.app.post.dto.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyToStoryRequest(
    @NotBlank @Size(max = 1000) String text
) {}
