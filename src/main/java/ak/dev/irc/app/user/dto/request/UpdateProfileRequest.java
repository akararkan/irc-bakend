package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 80)  String fname,
        @Size(max = 80)  String lname,
        @Size(max = 200) String location,
        String profileImage,
        String profileBio,
        String selfDescriber
) {}
