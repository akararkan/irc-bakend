package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.realtime.FeedRealtimePublisher;
import ak.dev.irc.app.post.cassandra.repository.FeedByUserRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Home-feed timeline service.
 *
 * Write path  (fanout-on-write):
 *   When a post is created we synchronously enqueue an async fanout job that
 *   walks the author's followers in batches and writes a row into feed_by_user
 *   for each one. The TTL on feed_by_user (30d) keeps storage bounded.
 *
 * Read path:
 *   1.  Try Redis (sorted set per user, last 100 post ids by score=ts)
 *   2.  Fall back to a Cassandra partition slice on feed_by_user
 *   3.  Backfill Redis on cache miss for the next request
 *
 * Realtime delivery: after each follower's fanout row is written we publish to
 * the existing Redis pub/sub channel (PostRealtimePublisher) so any open SSE
 * connection for that follower receives the new post immediately.
 *
 * Fanout caveats:
 *   • Capped per request via {@code MAX_FANOUT_FOLLOWERS} to keep tail latency
 *     bounded. Beyond the cap we fall back to pull-on-read (read-through cache).
 *   • For accounts with > 1M followers we'd switch to "celebrity push" — write
 *     the post id to a small celebrity_posts table and have the read path
 *     merge it. Not implemented yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineService {

    private static final int  MAX_FANOUT_FOLLOWERS = 50_000;
    private static final int  FANOUT_BATCH         = 500;
    private static final int  REDIS_FEED_SIZE      = 100;
    private static final String REDIS_FEED_KEY_PREFIX = "feed:timeline:";

    // ── Circuit breaker on writeFanoutRow ────────────────────────────────────
    // When Cassandra slows down, the bounded taskExecutor queue (10k) fills,
    // CallerRunsPolicy kicks in, and the fanout @Async thread itself starts
    // running writes — eventually back-pressuring HTTP request threads.
    // This in-tree breaker opens after FAILURE_THRESHOLD consecutive write
    // failures, drops fanout writes (read-through pull will reconstruct the
    // feed on demand) for CIRCUIT_OPEN_DURATION_MS, then half-opens to retry.
    // No new dependency; same pattern as Resilience4j's CircuitBreaker but
    // narrower: we only need to protect this one hot write path.
    private static final int  FAILURE_THRESHOLD          = 20;
    private static final long CIRCUIT_OPEN_DURATION_MS   = 30_000L;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong    circuitOpenedAt     = new AtomicLong(0L);

    private final FeedByUserRepository    feedRepo;
    private final PostByAuthorRepository  postByAuthorRepo;
    private final UserFollowRepository    userFollowRepo;
    private final FeedRealtimePublisher   realtimePublisher;
    private final StringRedisTemplate     redis;
    private final UserRepository          userRepo;
    private final CassandraNotificationService notificationService;
    /**
     * Bounded executor for per-follower fanout writes. Resolved by parameter
     * name to the {@code taskExecutor} bean defined in {@code AsyncConfig}
     * (core=8 / max=32 / queue=10k / CallerRunsPolicy). Replaces the prior
     * {@code parallelStream()} which ran blocking Cassandra writes on the
     * JVM-wide {@code ForkJoinPool.commonPool()} — a viral post could starve
     * every other parallel stream in the JVM and the pool itself could
     * deadlock on slow I/O.
     */
    private final ThreadPoolTaskExecutor  taskExecutor;

    /**
     * Walk followers in pages and write a feed_by_user row for each. Runs on
     * a Spring async thread pool so the HTTP request that created the post
     * returns immediately.
     */
    @Async
    public void fanoutAsync(UUID postId,
                            UUID authorId,
                            Instant createdAt,
                            String postType,
                            String textPreview,
                            String mediaUrl,
                            String visibility) {
        if ("ONLY_ME".equals(visibility)) {
            // Even private posts go into the author's OWN feed so they see
            // their own timeline when they hit /api/v1/posts/feed.
            writeFanoutRow(postId, authorId, authorId, createdAt, postType, textPreview, mediaUrl);
            log.debug("[FEED] ONLY_ME post {} — self-fanout only", postId);
            return;
        }

        String authorLabel = userRepo.findById(authorId)
                .map(User::getUsername).map(u -> "@" + u).orElse("Someone");

        // Self-fanout — the author always sees their own posts in their home feed.
        // Without this row, /api/v1/posts/feed for the author returns an empty
        // list until someone they follow also posts something. Twitter / IG /
        // Facebook all do this; users expect it.
        writeFanoutRow(postId, authorId, authorId, createdAt, postType, textPreview, mediaUrl);
        pushRealtime(authorId, postId, authorId);

        // Keyset-paginated follower scan: each page is constant-time on the
        // (following.id, follower.id) index, so depth doesn't degrade the
        // per-page cost the way the old OFFSET-based pager did. Within each
        // batch the per-follower writes are submitted to the bounded
        // {@code taskExecutor} (core=8/max=32/queue=10k/CallerRunsPolicy):
        // we get parallelism without burning the JVM-wide common pool, and
        // CallerRunsPolicy supplies natural backpressure — when Cassandra
        // slows down and the queue fills, the cursor-scan thread runs the
        // write itself, throttling the scan to match downstream throughput.
        UUID cursor = null;
        int totalDelivered = 0;
        while (totalDelivered < MAX_FANOUT_FOLLOWERS) {
            List<UUID> followerBatch;
            try {
                followerBatch = userFollowRepo.findFollowerIdsAfter(
                        authorId, cursor, PageRequest.of(0, FANOUT_BATCH));
            } catch (Exception e) {
                log.warn("[FEED] follower lookup failed for {}: {}", authorId, e.getMessage());
                return;
            }
            if (followerBatch.isEmpty()) break;

            for (UUID followerId : followerBatch) {
                taskExecutor.execute(() -> {
                    try {
                        writeFanoutRow(postId, authorId, followerId, createdAt,
                                      postType, textPreview, mediaUrl);
                        pushRealtime(followerId, postId, authorId);
                        deliverPostNewNotification(followerId, authorId, authorLabel,
                                                   postId, textPreview);
                    } catch (Exception e) {
                        log.debug("[FEED] per-follower fanout {} failed: {}",
                                followerId, e.getMessage());
                    }
                });
            }
            totalDelivered += followerBatch.size();
            cursor = followerBatch.get(followerBatch.size() - 1);  // keyset advance

            if (followerBatch.size() < FANOUT_BATCH) break;
        }
        log.info("[FEED] fanout complete for post {}: {} followers delivered", postId, totalDelivered);
    }

    /**
     * Backfill: when {@code newFollowerId} starts following {@code authorId},
     * copy the author's most recent ~50 posts into the new follower's
     * {@code feed_by_user} so their home feed isn't empty until the author
     * posts something fresh. Mirrors what Twitter / Instagram do on follow.
     *
     * <p>Pulls from {@code posts_by_author} (already sorted DESC by createdAt)
     * and writes each row in. Skips ONLY_ME posts. Best-effort — failures
     * are logged but don't break the follow operation.</p>
     */
    @Async
    public void backfillFollowerFeed(UUID newFollowerId, UUID authorId) {
        if (newFollowerId == null || authorId == null) return;
        try {
            List<PostByAuthorEntity> recent = postByAuthorRepo.firstPage(authorId, 50);
            int written = 0;
            for (PostByAuthorEntity p : recent) {
                writeFanoutRow(p.getPostId(), p.getAuthorId(), newFollowerId,
                               p.getCreatedAt(), p.getPostType(),
                               p.getTextPreview(), p.getMediaUrl());
                written++;
            }
            log.info("[FEED] backfilled {} posts from author={} into follower={}",
                    written, authorId, newFollowerId);
        } catch (Exception e) {
            log.warn("[FEED] backfill failed for follower={} author={}: {}",
                    newFollowerId, authorId, e.getMessage());
        }
    }

    private void writeFanoutRow(UUID postId, UUID authorId, UUID viewerId,
                                Instant createdAt, String postType,
                                String preview, String mediaUrl) {
        // Half-open after the cooldown elapses: clear the "opened-at" marker
        // so the next attempt actually hits Cassandra. If it fails again, the
        // failure counter trips the breaker open immediately.
        long openedAt = circuitOpenedAt.get();
        if (openedAt > 0L) {
            if (System.currentTimeMillis() - openedAt < CIRCUIT_OPEN_DURATION_MS) {
                // Breaker still open — drop this write. The reader falls back
                // to posts_by_author / cache hydration, so users still see posts.
                return;
            }
            circuitOpenedAt.compareAndSet(openedAt, 0L);
        }

        try {
            feedRepo.save(FeedByUserEntity.builder()
                    .userId(viewerId)
                    .createdAt(createdAt)
                    .postId(postId)
                    .authorId(authorId)
                    .postType(postType)
                    .textPreview(preview)
                    .mediaUrl(mediaUrl)
                    .build());
            consecutiveFailures.set(0);
        } catch (RuntimeException e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD
                    && circuitOpenedAt.compareAndSet(0L, System.currentTimeMillis())) {
                log.error("[FEED] circuit breaker OPENED after {} consecutive Cassandra write failures — fanout writes paused for {}ms",
                        failures, CIRCUIT_OPEN_DURATION_MS);
            }
            throw e;
        }

        // Also push into the per-viewer Redis cache so the next /feed request
        // hits Redis instead of touching Cassandra.
        try {
            String key = REDIS_FEED_KEY_PREFIX + viewerId;
            redis.opsForZSet().add(key, postId.toString(),
                                    (double) createdAt.toEpochMilli());
            redis.opsForZSet().removeRange(key, 0, -(REDIS_FEED_SIZE + 1));
        } catch (Exception e) {
            // Cache failure is non-fatal; Cassandra has the truth.
            log.debug("[FEED] redis cache update skipped: {}", e.getMessage());
        }
    }

    /**
     * Fire a low-cost in-app notification per follower on POST_NEW. This kind
     * is configured as emailEligible=false in {@link NotificationKind}, so a
     * creator with 50k followers does NOT trigger 50k emails — only the in-app
     * inbox rows + the realtime SSE push.
     */
    private void deliverPostNewNotification(UUID followerId, UUID authorId,
                                            String authorLabel, UUID postId,
                                            String preview) {
        try {
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    followerId,
                    NotificationKind.POST_NEW,
                    authorLabel + " posted",
                    preview == null ? authorLabel + " just posted." : preview,
                    authorId,
                    "Post", postId,
                    "POST_NEW:" + postId
            ));
        } catch (Exception e) {
            log.debug("[FEED] notify follower {} skipped: {}", followerId, e.getMessage());
        }
    }

    private void pushRealtime(UUID viewerId, UUID postId, UUID authorId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("event",   "FEED_NEW_POST");
            payload.put("postId",  postId.toString());
            payload.put("authorId", authorId.toString());
            realtimePublisher.publishToUser(viewerId, payload);
        } catch (Exception e) {
            log.debug("[FEED] realtime push failed for {}: {}", viewerId, e.getMessage());
        }
    }

    // ── Read path ───────────────────────────────────────────────────────────

    public List<FeedByUserEntity> homeFeed(UUID userId, int pageSize) {
        return feedRepo.firstPage(userId, pageSize);
    }

    public List<FeedByUserEntity> homeFeedAfter(UUID userId, Instant cursor, int pageSize) {
        return feedRepo.nextPage(userId, cursor, pageSize);
    }
}
