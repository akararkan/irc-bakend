package ak.dev.irc.app.activity.service;

import ak.dev.irc.app.activity.dto.ReelViewResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

public interface ReelViewService {

    ReelViewResponse recordWatch(UUID userId, UUID postId, Integer watchedSeconds);

    /**
     * Bump the underlying post's view counter and broadcast the fresh number
     * on the post's realtime channel. Idempotent within the Redis dedupe
     * window. Runs in its own {@code REQUIRES_NEW} transaction so the read
     * uses a clean L1 cache and the broadcast carries the post-increment
     * value (not a stale entity from the caller's session).
     */
    void recordPostView(UUID postId, UUID viewerId);

    Page<ReelViewResponse> listMyWatched(UUID userId, Pageable pageable);

    /**
     * Post ids of the viewer's most recent watch sessions (newest first, up to
     * {@code limit} rows) — the "already seen" set behind badges such as the
     * Following tab's unwatched-reels count. One partition read, no paging.
     */
    Set<UUID> recentlyWatchedPostIds(UUID userId, int limit);

    void deleteOne(UUID userId, UUID reelViewId);

    int deleteAll(UUID userId);
}
