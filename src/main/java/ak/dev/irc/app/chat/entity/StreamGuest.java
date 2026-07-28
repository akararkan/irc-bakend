package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.StreamGuestStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A viewer's seat on a {@link LiveStream}'s <b>stage</b> — the TikTok-style
 * multi-guest panel where the host lets people come "up" and talk beside them.
 *
 * <p>One row per (stream, user); the {@link StreamGuestStatus} drives the whole
 * request → approve → on-stage → off flow. When a guest is {@code ACTIVE} it holds
 * its <b>own</b> media path: {@code publishPath} is a fresh id the guest publishes
 * their camera to over WebRTC/WHIP, gated by the secret {@code publishKey} — so a
 * guest's publish credential is theirs alone and never the host's stream key.
 * Everyone (host, other guests, viewers) subscribes to {@code publishPath}/whep to
 * see the guest. On step-down/removal the path is abandoned and the key revoked.</p>
 */
@Entity
@Table(
    name = "stream_guests",
    uniqueConstraints = @UniqueConstraint(name = "uk_stream_guest", columnNames = {"stream_id", "user_id"}),
    indexes = {
        @Index(name = "idx_stream_guest_status", columnList = "stream_id, status"),
        @Index(name = "idx_stream_guest_path", columnList = "publish_path")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamGuest extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private StreamGuestStatus status = StreamGuestStatus.REQUESTED;

    /** Host-set mute. Authoritative: every client mutes this guest's audio when
     *  {@code true}, so a muted guest is silent for everyone, not just the host. */
    @Column(name = "muted", nullable = false)
    @Builder.Default
    private boolean muted = false;

    /** The guest's own media path (its own stream id on the media server). Set
     *  when the guest goes {@code ACTIVE}; null otherwise. The WHEP subscribe URL
     *  is public; publishing to it requires {@link #publishKey}. */
    @Column(name = "publish_path", length = 40)
    private String publishPath;

    /** Secret publish credential for {@link #publishPath} — returned only to the
     *  guest themselves (never in the roster broadcast). Null unless ACTIVE. */
    @Column(name = "publish_key", length = 64)
    private String publishKey;

    @Column(name = "requested_at")
    private Instant requestedAt;

    /** When the guest last went ACTIVE (came up on stage). */
    @Column(name = "joined_at")
    private Instant joinedAt;

    /** When the guest last left the stage (stepped down / removed / stream ended). */
    @Column(name = "left_at")
    private Instant leftAt;
}
