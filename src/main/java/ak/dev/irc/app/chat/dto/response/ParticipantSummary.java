package ak.dev.irc.app.chat.dto.response;

import java.util.UUID;

/** Lightweight user summary embedded in conversation/message/member payloads.
 *  Avatars are hydrated client-side via the existing user endpoints. */
public record ParticipantSummary(
        UUID userId,
        String username,
        String fullName
) {}
