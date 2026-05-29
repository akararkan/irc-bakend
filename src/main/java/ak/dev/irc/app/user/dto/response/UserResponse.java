package ak.dev.irc.app.user.dto.response;

import ak.dev.irc.app.user.enums.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID            id,
    String          fname,
    String          lname,
    String          username,
    String          email,
    Role            role,
    List<BadgeDto>  badges,
    boolean         isEmailVerified,
    ProfileResponse profile,
    LocalDateTime   createdAt
) {}
