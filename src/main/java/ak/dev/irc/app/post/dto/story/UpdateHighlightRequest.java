package ak.dev.irc.app.post.dto.story;

import jakarta.validation.constraints.Size;

public record UpdateHighlightRequest(
    @Size(max = 80) String title,
    Integer displayOrder
) {}
