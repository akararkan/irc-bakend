package ak.dev.irc.app.post.dto.story;

import ak.dev.irc.app.post.enums.StoryVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTextStoryRequest(
    @NotBlank @Size(max = 500) String textContent,
    StoryVisibility visibility,
    String backgroundType,
    String backgroundValue,
    String overlaysJson,
    java.util.UUID soundId,
    int soundClipStartSeconds,
    float soundVolume
) {}
