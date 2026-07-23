package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.chat.enums.CallSignalType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** A WebRTC signaling frame the caller wants relayed to one peer in the call. */
@Data
public class CallSignalRequest {
    @NotNull
    private UUID toUserId;
    @NotNull
    private CallSignalType kind;   // OFFER | ANSWER | ICE
    /** Opaque SDP or ICE-candidate JSON — relayed verbatim, never inspected. */
    private String payload;
}
