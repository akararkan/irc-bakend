package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.CallStatus;
import ak.dev.irc.app.chat.enums.CallType;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A voice/video call bound to a conversation (DM or group). This is the call's
 * control-plane record — lifecycle + signaling only. The audio/video media flows
 * peer-to-peer over WebRTC and never passes through the server.
 */
@Entity
@Table(
    name = "call_sessions",
    indexes = {
        @Index(name = "idx_call_conversation", columnList = "conversation_id"),
        @Index(name = "idx_call_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallSession extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "initiator_id", nullable = false)
    private UUID initiatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private CallType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private CallStatus status = CallStatus.RINGING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 40)
    private String endReason;

    public boolean isActive() {
        return status == CallStatus.RINGING || status == CallStatus.ONGOING;
    }
}
