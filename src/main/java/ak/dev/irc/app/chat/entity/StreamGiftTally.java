package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Running per-stream, per-sender gift total — the source of a live stream's
 * "top supporters" leaderboard. One row per (stream, user), incremented on every
 * gift that user sends into that stream. The individual gift-send is broadcast and
 * animated but never stored on its own; only this aggregate persists, so the
 * leaderboard survives a page reload and outlives the ephemeral animation.
 *
 * <p>Coins are the symbolic weight from {@link ak.dev.irc.app.chat.enums.StreamGift}
 * — a ranking score, never money.</p>
 */
@Entity
@Table(
    name = "stream_gift_tallies",
    uniqueConstraints = @UniqueConstraint(name = "uk_stream_gift_tally", columnNames = {"stream_id", "user_id"}),
    indexes = @Index(name = "idx_stream_gift_tally_board", columnList = "stream_id, coins")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamGiftTally extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Total symbolic coins this sender has gifted into this stream. */
    @Column(name = "coins", nullable = false)
    @Builder.Default
    private long coins = 0;

    /** How many gifts this sender has sent into this stream. */
    @Column(name = "gift_count", nullable = false)
    @Builder.Default
    private int giftCount = 0;

    @Column(name = "last_gift_at")
    private Instant lastGiftAt;
}
