package ak.dev.irc.app.post.dto.story;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record StoryHighlightResponse(
    UUID            id,
    UUID            authorId,
    String          title,
    String          coverUrl,
    int             displayOrder,
    int             storyCount,
    List<StoryResponse> stories,
    LocalDateTime   createdAt
) {}
