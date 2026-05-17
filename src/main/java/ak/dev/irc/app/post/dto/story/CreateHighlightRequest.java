package ak.dev.irc.app.post.dto.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHighlightRequest(
    @NotBlank @Size(max = 80) String title,
    int displayOrder
) {}
