package ak.dev.irc.app.chat.dto.response;

import java.util.UUID;

/** A relayed WebRTC signaling frame delivered over SSE to the target peer. */
public record CallSignalMessage(
        UUID callId,
        UUID fromUserId,
        String kind,       // OFFER | ANSWER | ICE
        String payload
) {}
