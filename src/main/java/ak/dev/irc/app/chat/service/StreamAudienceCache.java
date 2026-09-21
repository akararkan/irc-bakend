package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.entity.StreamGuest;
import ak.dev.irc.app.chat.enums.StreamGuestStatus;
import ak.dev.irc.app.chat.repository.StreamGuestRepository;
import ak.dev.irc.app.chat.repository.StreamViewerRepository;
import ak.dev.irc.app.common.cache.TtlCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Short-TTL, in-process cache for a live stream's "who's watching right now"
 * data, shared by {@link StreamStageService} (reaction/gift fan-out + the
 * stage roster) and {@link LiveStreamService} (viewer join/leave/count + live
 * chat fan-out) — both ultimately read the exact same two queries
 * ({@link StreamViewerRepository#findActiveViewerIds} and
 * {@link StreamGuestRepository#findByStreamIdAndStatusOrderByJoinedAtAsc}).
 *
 * <p>Without this, a popular stream with several concurrently active senders
 * re-ran {@code findActiveViewerIds} on <em>every single</em> reaction/gift
 * tap (rate-limited to up to 30/10s and 10/10s per user) — the single largest
 * DB load driver in the live-stage feature.</p>
 *
 * <p><b>TTL 3s.</b> A fanout recipient list can be at most one broadcast
 * stale or one extra on the rare edge, which is harmless. That bound only
 * matters for the steady state (viewer list unchanged between taps); any
 * caller that just changed the underlying state calls {@link #invalidate}
 * first, so the very next read after a join/leave/guest-status-change is
 * always a fresh DB read that also re-warms the cache for the taps that
 * follow it.</p>
 */
@Component
@RequiredArgsConstructor
public class StreamAudienceCache {

    private static final Duration TTL = Duration.ofSeconds(3);

    private final StreamViewerRepository viewerRepo;
    private final StreamGuestRepository guestRepo;

    private final TtlCache<UUID, List<UUID>> viewerIdsCache = new TtlCache<>(TTL);
    private final TtlCache<UUID, List<StreamGuest>> activeGuestsCache = new TtlCache<>(TTL);

    /** The user ids currently actively watching {@code streamId}. */
    public List<UUID> activeViewerIds(UUID streamId) {
        return viewerIdsCache.get(streamId, () -> viewerRepo.findActiveViewerIds(streamId));
    }

    /** The stage's active guests, oldest-first (stable stage order). */
    public List<StreamGuest> activeGuests(UUID streamId) {
        return activeGuestsCache.get(streamId, () ->
                guestRepo.findByStreamIdAndStatusOrderByJoinedAtAsc(streamId, StreamGuestStatus.ACTIVE));
    }

    /**
     * Drop both cached entries for a stream. Call right after a write that
     * changes viewer presence or guest/stage status (join, leave, promote,
     * remove, mute, or the stream itself ending), so the caller's own
     * follow-up read — which typically needs the change to be visible right
     * away, e.g. a fresh viewer count or the post-change roster broadcast —
     * never sees stale data. Relying on the TTL alone would allow up to
     * {@code TTL} of staleness on exactly the calls that most need the
     * change reflected immediately.
     */
    public void invalidate(UUID streamId) {
        viewerIdsCache.invalidate(streamId);
        activeGuestsCache.invalidate(streamId);
    }
}
