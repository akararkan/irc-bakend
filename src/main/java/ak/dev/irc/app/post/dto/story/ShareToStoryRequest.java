package ak.dev.irc.app.post.dto.story;

import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ShareToStoryRequest(
    @NotNull UUID linkedContentId,
    @NotNull StoryType storyType,
    StoryVisibility visibility,
    String caption
) {}
