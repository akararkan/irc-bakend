package ak.dev.irc.app.chat.entity;

import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A live stream's control-plane record: lifecycle, host, discovery metadata, and
 * a live viewer count. The audio/video is ingested to and served from an external
 * media server (RTMP/WebRTC in, HLS/WebRTC out) addressed by {@code streamKey} —
 * this app owns the metadata, presence, and live chat, not the media path.
 */
@Entity
@Table(
    name = "live_streams",
    indexes = {
        @Index(name = "idx_stream_status", columnList = "status"),
        @Index(name = "idx_stream_host", columnList = "host_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveStream extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "title", nullable = false, length = 140)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private LiveStreamStatus status = LiveStreamStatus.LIVE;

    /** Secret ingest key — only ever returned to the host. */
    @Column(name = "stream_key", nullable = false, length = 64)
    private String streamKey;

    @Column(name = "viewer_count", nullable = false)
    @Builder.Default
    private int viewerCount = 0;

    @Column(name = "peak_viewer_count", nullable = false)
    @Builder.Default
    private int peakViewerCount = 0;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
