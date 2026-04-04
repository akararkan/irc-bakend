package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.LinkPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddLinkRequest(
        @NotNull
        LinkPlatform platform,

        @NotBlank @Size(max = 200)
        String description,

        @NotBlank
        String url,

        boolean isPublic,

        int displayOrder
) {}
