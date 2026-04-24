package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 80)  String fname,
        @Size(max = 80)  String lname,
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                 message = "Username may only contain letters, digits, dots, hyphens, and underscores")
        String username,
        @Size(max = 200) String location,
        String profileImage,
        String profileBio,
        String selfDescriber,
        Boolean isProfileLocked
) {}
