package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.CallParticipantState;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One invitee's membership + state within a {@link CallSession}. */
@Entity
@Table(
    name = "call_participants",
    uniqueConstraints = @UniqueConstraint(name = "uk_call_participant", columnNames = {"call_id", "user_id"}),
    indexes = @Index(name = "idx_call_participant_call", columnList = "call_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallParticipant extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "call_id", nullable = false)
    private UUID callId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    @Builder.Default
    private CallParticipantState state = CallParticipantState.INVITED;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;
}
