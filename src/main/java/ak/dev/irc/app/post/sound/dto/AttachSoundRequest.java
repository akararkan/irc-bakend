package ak.dev.irc.app.post.sound.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AttachSoundRequest(
    @NotNull UUID soundId,
    @Min(0) int clipStartSeconds,
    @Min(0) @Max(1) float volume
) {}
