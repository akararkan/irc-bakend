package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.LinkPlatform;
import jakarta.validation.constraints.Size;

public record EditLinkRequest(
        LinkPlatform platform,
        @Size(max = 200) String description,
        String url,
        Boolean isPublic,
        Integer displayOrder
) {}
