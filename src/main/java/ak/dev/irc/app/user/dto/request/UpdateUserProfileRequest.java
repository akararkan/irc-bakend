package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
    @Size(max = 120) String  displayName,
    String                   profileBio,
    @Size(max = 200) String  selfDescriber,
    @Size(max = 200) String  location,
    @Size(max = 150) String  academicTitle,
    @Size(max = 200) String  institutionName,
    Integer                  madhhabId,
    String                   websiteUrl,
    Boolean                  isForHire,
    Boolean                  isProfileLocked,
    String                   contentLanguage
) {}
