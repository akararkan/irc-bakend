package ak.dev.irc.app.chat.dto.response;

import java.util.UUID;

/** Presence snapshot for a user. {@code status} is "online" or "offline". */
public record PresenceResponse(
        UUID userId,
        String status,
        Long lastSeenEpochMs
) {}
