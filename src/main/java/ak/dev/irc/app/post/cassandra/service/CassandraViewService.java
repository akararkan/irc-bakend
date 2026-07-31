package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.ViewByPostEntity;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ViewByPostRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Unique-viewer tracking, backed by Cassandra.
 *
 * The counter is bumped only on a user's FIRST view of a post — repeat
 * views from the same user must not inflate the count.
 *
 * Idempotency strategy:
 *   1.  Redis SET NX on key "view:{postId}:{userId}" with a 7-day TTL
 *       — answers "have we already counted this user this week?" in O(1).
 *   2.  On NX success: write views_by_post row + bump post_counters.view_count.
 *   3.  On NX failure (key existed): no-op.
 *
 * Why Redis instead of LWT:
 *   Cassandra LWT (INSERT … IF NOT EXISTS) does a Paxos round-trip and
 *   bottlenecks under hot keys. Redis NX is sub-millisecond and the data
 *   the source-of-truth (views_by_post) is still strongly partition-keyed
 *   so we get reconciliation for free if Redis is ever cleared.
 *
 * If Redis is unreachable we fall back to reading views_by_post first —
 * slower but still correct.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraViewService {

    private static final Duration VIEW_DEDUPE_TTL = Duration.ofDays(7);
    private static final String   REDIS_KEY_PREFIX = "view:";

    private final ViewByPostRepository  viewRepo;
    private final PostCounterRepository postCounterRepo;
    private final CounterService        counterService;
    private final PostRealtimePublisher realtimePublisher;
    private final StringRedisTemplate   redis;
    private final AuthorAffinityService affinityService;

    /**
     * Record that user U viewed post P. Bumps the unique view counter iff
     * this is the user's first view inside the dedupe window.
     *
     * @return true if a fresh view was counted, false if this was a duplicate.
     */
    public boolean recordView(UUID postId, UUID userId) {
        if (alreadyCounted(postId, userId)) return false;

        try {
            viewRepo.save(ViewByPostEntity.builder()
                    .postId(postId)
                    .userId(userId)
                    .firstViewedAt(Instant.now())
                    .build());
            counterService.incrementPostViews(postId);
            broadcast(postId, userId);
            affinityService.recordForPost(userId, postId, AuthorAffinityService.WEIGHT_VIEW);
            return true;
        } catch (Exception e) {
            log.warn("[VIEW] record failed for post {} user {}: {}", postId, userId, e.getMessage());
            // The NX dedupe key was claimed BEFORE this write; leaving it in
            // place would suppress the user's view for the whole 7-day TTL
            // even though nothing was recorded. Release it so a retry counts.
            releaseDedupeKey(postId, userId);
            return false;
        }
    }

    public Long viewCount(UUID postId) {
        return postCounterRepo.findByPostId(postId)
                .map(c -> c.getViewCount())
                .orElse(0L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void releaseDedupeKey(UUID postId, UUID userId) {
        try {
            redis.delete(REDIS_KEY_PREFIX + postId + ":" + userId);
        } catch (Exception e) {
            log.debug("[VIEW] dedupe-key release failed (view stays suppressed until TTL): {}",
                    e.getMessage());
        }
    }

    private boolean alreadyCounted(UUID postId, UUID userId) {
        try {
            Boolean firstWrite = redis.opsForValue()
                    .setIfAbsent(REDIS_KEY_PREFIX + postId + ":" + userId, "1", VIEW_DEDUPE_TTL);
            // Successful NX (firstWrite=true) means we DID NOT have a prior view recorded.
            if (Boolean.TRUE.equals(firstWrite)) return false;
            if (Boolean.FALSE.equals(firstWrite)) return true;
            // null = Redis is unreachable; fall through to Cassandra read
        } catch (Exception e) {
            log.debug("[VIEW] redis dedupe unavailable, falling back to Cassandra: {}", e.getMessage());
        }
        return viewRepo.find(postId, userId).isPresent();
    }

    // Counter columns are eventually consistent — clients apply +1 locally
    // and reconcile via REST on next fetch.
    private void broadcast(UUID postId, UUID viewerId) {
        try {
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.VIEW_COUNT_UPDATED)
                    .postId(postId)
                    .actorId(viewerId)
                    .build());
        } catch (Exception e) {
            log.debug("[VIEW] realtime broadcast skipped: {}", e.getMessage());
        }
    }
}
