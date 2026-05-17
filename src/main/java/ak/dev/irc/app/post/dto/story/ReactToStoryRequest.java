package ak.dev.irc.app.post.dto.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReactToStoryRequest(
    @NotBlank @Size(max = 10) String emoji
) {}
