package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.ContactPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddContactRequest(
        @NotNull
        ContactPlatform platform,

        @NotBlank @Size(max = 200)
        String value,

        boolean isPublic
) {}
