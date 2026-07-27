package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A live stream's public state. {@code whipUrl} and {@code ingestUrl} are populated
 *  only when the response is returned to the host (they carry the secret stream key). */
public record LiveStreamResponse(
        UUID id,
        UUID hostId,
        /** Host handle — drives the "@name is live" label on the live row. */
        String hostUsername,
        /** Host display name (first + last). */
        String hostDisplayName,
        /** Host avatar — the ring shown on the live row / watch page. Nullable. */
        String hostAvatarUrl,
        String title,
        String description,
        String status,        // LIVE | ENDED
        /** HLS playback (universal, higher latency). Public. */
        String playbackUrl,
        /** WebRTC WHEP playback (sub-second latency). Public. */
        String whepUrl,
        /** WebRTC WHIP publish — the browser camera goes live here. Host-only; null for viewers. */
        String whipUrl,
        /** RTMP publish for OBS/ffmpeg/desktop encoders. Host-only; null for viewers. */
        String ingestUrl,
        int viewerCount,
        Instant startedAt,
        Instant endedAt,
        /** Shareable watch link ({@code {base}/live/{id}}) — safe for anyone. */
        String shareUrl,
        /** Recording lifecycle — {@code RecordingStatus} name (DISABLED while off). */
        String recordingStatus,
        /** True when a recording is finished and downloadable by the owner. */
        boolean recordingAvailable,
        /** Owner-only API path to download the recording; null unless available. */
        String recordingDownloadUrl
) {}
