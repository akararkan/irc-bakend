package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Registry of who is currently watching a {@link LiveStream} — drives the live
 *  viewer count and the recipient set for live-chat / viewer / ended events. */
@Entity
@Table(
    name = "stream_viewers",
    uniqueConstraints = @UniqueConstraint(name = "uk_stream_viewer", columnNames = {"stream_id", "user_id"}),
    indexes = @Index(name = "idx_stream_viewer_active", columnList = "stream_id, active")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamViewer extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;
}
