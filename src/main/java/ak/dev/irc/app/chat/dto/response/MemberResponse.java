package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** A group member with role + status. */
public record MemberResponse(
        UUID userId,
        String username,
        String fullName,
        String role,
        String status,
        LocalDateTime joinedAt
) {}
