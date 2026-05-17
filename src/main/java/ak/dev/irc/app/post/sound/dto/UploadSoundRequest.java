package ak.dev.irc.app.post.sound.dto;

import ak.dev.irc.app.post.enums.SoundCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UploadSoundRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 150) String artistName,
    String description,
    @NotNull SoundCategory category,
    String sourceUrl,
    String license
) {}
