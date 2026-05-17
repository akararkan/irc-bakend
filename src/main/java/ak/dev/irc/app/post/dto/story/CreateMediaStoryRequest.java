package ak.dev.irc.app.post.dto.story;

import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import jakarta.validation.constraints.NotNull;

public record CreateMediaStoryRequest(
    @NotNull StoryType storyType,
    StoryVisibility visibility,
    String textContent,
    String overlaysJson,
    java.util.UUID soundId,
    int soundClipStartSeconds,
    float soundVolume
) {}
