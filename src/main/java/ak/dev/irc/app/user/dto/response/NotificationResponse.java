package ak.dev.irc.app.user.dto.response;


import ak.dev.irc.app.user.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID             id,
        NotificationType type,
        String           title,
        String           body,
        UUID             actorId,
        String           actorUsername,
        String           actorProfileImage,
        UUID             resourceId,
        String           resourceType,
        boolean          isRead,
        LocalDateTime    readAt,
        LocalDateTime    createdAt
) {}
