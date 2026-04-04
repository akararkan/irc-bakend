package ak.dev.irc.app.user.dto.response;


import ak.dev.irc.app.user.enums.ContactPlatform;

import java.util.UUID;

public record UserContactResponse(
        UUID            id,
        ContactPlatform platform,
        String          value,
        boolean         isPublic
) {}
