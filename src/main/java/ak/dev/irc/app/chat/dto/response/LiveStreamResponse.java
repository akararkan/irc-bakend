package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A live stream's public state. {@code ingestUrl} is populated only when the
 *  response is returned to the host (it carries the secret stream key). */
public record LiveStreamResponse(
        UUID id,
        UUID hostId,
        String title,
        String description,
        String status,        // LIVE | ENDED
        String playbackUrl,
        String ingestUrl,      // host-only; null for viewers
        int viewerCount,
        Instant startedAt,
        Instant endedAt,
        /** Shareable watch link ({@code {base}/live/{id}}) — safe for anyone. */
        String shareUrl
) {}
