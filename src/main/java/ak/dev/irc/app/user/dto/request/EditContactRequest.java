package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.ContactPlatform;
import jakarta.validation.constraints.Size;

public record EditContactRequest(
        ContactPlatform platform,
        @Size(max = 200) String value,
        Boolean isPublic
) {}
