package ak.dev.irc.app.post.dto.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VotePollRequest(
    @NotBlank @Pattern(regexp = "^[AB]$", message = "Choice must be A or B") String choice
) {}
