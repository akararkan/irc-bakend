package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A call's control-plane state — returned by the REST API and carried on the
 *  {@code call.*} realtime events. */
public record CallResponse(
        UUID id,
        UUID conversationId,
        UUID initiatorId,
        String type,       // VOICE | VIDEO
        String status,     // RINGING | ONGOING | ENDED | DECLINED | MISSED | CANCELLED
        List<CallParticipantResponse> participants,
        Instant startedAt,
        Instant answeredAt,
        Instant endedAt
) {}
