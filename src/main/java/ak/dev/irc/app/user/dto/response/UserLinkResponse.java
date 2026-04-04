package ak.dev.irc.app.user.dto.response;


import ak.dev.irc.app.user.enums.LinkPlatform;

import java.util.UUID;

public record UserLinkResponse(
        UUID id,
        LinkPlatform platform,
        String       description,
        String       url,
        boolean      isPublic,
        int          displayOrder
) {}
