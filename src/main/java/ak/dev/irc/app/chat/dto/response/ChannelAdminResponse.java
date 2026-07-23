package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.chat.dto.AdminRights;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the channel's admin list (owner included). */
public record ChannelAdminResponse(
        UUID userId,
        String username,
        String fullName,
        String role,
        /** Effective rights — full rights rendered explicitly for legacy admins. */
        AdminRights rights,
        LocalDateTime since
) {}
