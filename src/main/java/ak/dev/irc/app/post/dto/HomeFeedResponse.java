package ak.dev.irc.app.post.dto;

import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;

import java.time.Instant;
import java.util.List;

/**
 * Composite home-feed payload — the canonical v2 response for
 * {@code GET /api/v1/posts/feed/home}.
 *
 * <ul>
 *   <li>{@code items} — the ranked feed page (posts, research, questions,
 *       channel posts, exploration items), best-first.</li>
 *   <li>{@code liveNow} — the live rail: streams from followed hosts first,
 *       topped up with the most-watched public streams. First page only;
 *       empty on cursor pages.</li>
 *   <li>{@code nextCursor} — pass back as {@code ?cursor=} for the next
 *       page. Computed from the raw timeline window (NOT the ranked order),
 *       so pagination never skips or loops. {@code null} = end of feed.</li>
 *   <li>{@code ranked} — false when the caller asked for the chronological
 *       fallback ({@code ?ranked=false}).</li>
 * </ul>
 */
public record HomeFeedResponse(
        List<FeedItemResponse> items,
        List<LiveStreamResponse> liveNow,
        Instant nextCursor,
        boolean ranked
) {}
