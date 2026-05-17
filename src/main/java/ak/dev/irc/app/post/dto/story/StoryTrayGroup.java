package ak.dev.irc.app.post.dto.story;

import java.util.List;
import java.util.UUID;

public record StoryTrayGroup(
    UUID   authorId,
    String authorUsername,
    String authorAvatarUrl,
    boolean hasUnseenStories,
    int    storyCount,
    List<StoryResponse> stories
) {}
