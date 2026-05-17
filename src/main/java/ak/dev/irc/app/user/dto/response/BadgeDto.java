package ak.dev.irc.app.user.dto.response;

import ak.dev.irc.app.user.enums.BadgeType;

public record BadgeDto(
    BadgeType type,
    String    label,
    String    colorKey,
    String    icon,
    int       priority
) {}
