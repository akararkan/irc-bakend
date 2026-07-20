package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostCounterEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReelsByDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reel-specific feed algorithms.
 *
 * Two feeds:
 *
 *   1. Following reels  — reels only from accounts the viewer follows.
 *      Algorithm: parallel fan-in from posts_by_author per followed author
 *      (REEL filter via ALLOW FILTERING within partition), merge-sort DESC.
 *      Complexity: O(F × P) Cassandra reads, all parallel; F capped at
 *      MAX_FOLLOWING_FANIN to bound the fanout.
 *
 *   2. For You reels    — engagement-ranked pool from the last FOR_YOU_DAYS
 *      day buckets.  No ML required — pure counter math + recency decay.
 *      Score = (reactions×3 + comments×2 + views + 1) × e^(-age_h/48) × boost
 *      where boost = 1.5 if the viewer follows the author, else 1.0.
 *      Complexity: FOR_YOU_DAYS Cassandra reads + 1 bulk counter IN-query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReelFeedService {

    private static final int MAX_FOLLOWING_FANIN = 500;  // max parallel Cassandra reads
    private static final int PER_AUTHOR_LIMIT    = 5;    // reels fetched per followed author
    private static final int FOR_YOU_DAYS        = 3;    // day buckets to scan
    private static final int FOR_YOU_POOL        = 200;  // candidate pool before ranking

    private final PostByAuthorRepository postByAuthorRepo;
    private final ReelsByDayRepository   reelsByDayRepo;
    private final PostCounterRepository  postCounterRepo;
    private final FollowingIdsCache      followingIdsCache;
    /** Bounded shared pool (AsyncConfig) — used for the per-author fan-in reads. */
    private final ThreadPoolTaskExecutor taskExecutor;

    // ── Following Reels ──────────────────────────────────────────────────────

    /**
     * Reels from accounts the viewer follows, newest first, cursor-paginated.
     *
     * Each followed author's posts_by_author partition is read concurrently
     * on the bounded {@code taskExecutor} — NOT parallelStream, whose
     * ForkJoin common pool is JVM-wide: hundreds of blocking driver reads
     * there starve every other parallel stream in the process (the same
     * hazard FeedTimelineService documents for the write path). Results are
     * merge-sorted by createdAt DESC and the top pageSize rows returned.
     */
    public List<PostByAuthorEntity> followingReels(UUID viewerId, int pageSize, Instant cursor) {
        List<UUID> following = followingIdsCache.getFilteredFollowingIds(viewerId);
        if (following.isEmpty()) return List.of();

        List<UUID> capped = following.size() > MAX_FOLLOWING_FANIN
                ? following.subList(0, MAX_FOLLOWING_FANIN)
                : following;

        List<CompletableFuture<List<PostByAuthorEntity>>> futures = capped.stream()
                .map(authorId -> CompletableFuture.supplyAsync(
                        () -> fetchAuthorReels(authorId, cursor), taskExecutor))
                .toList();

        return futures.stream()
                .flatMap(f -> {
                    try {
                        return f.join().stream();
                    } catch (Exception e) {
                        log.debug("[REEL-FOLLOWING] author fan-in future failed: {}", e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .sorted(Comparator.comparing(PostByAuthorEntity::getCreatedAt).reversed())
                .limit(pageSize)
                .collect(Collectors.toList());
    }

    private List<PostByAuthorEntity> fetchAuthorReels(UUID authorId, Instant cursor) {
        try {
            return cursor == null
                    ? postByAuthorRepo.reelsByAuthor(authorId, PER_AUTHOR_LIMIT)
                    : postByAuthorRepo.reelsByAuthorAfter(authorId, cursor, PER_AUTHOR_LIMIT);
        } catch (Exception e) {
            log.debug("[REEL-FOLLOWING] fetch failed for author={}: {}", authorId, e.getMessage());
            return List.of();
        }
    }

    // ── For You Reels ────────────────────────────────────────────────────────

    /**
     * Engagement-ranked "For You" reel feed.
     *
     * Step 1 — collect: read up to FOR_YOU_POOL rows from the last
     *   FOR_YOU_DAYS day buckets of reels_by_day.
     *
     * Step 2 — enrich: one bulk IN-query to post_counters for all candidate IDs.
     *
     * Step 3 — score each reel:
     *   engagement  = reactions×3 + comments×2 + views
     *   recency     = e^(-ageHours / 48)       ← 48-hour half-life
     *   followBoost = 1.5 if viewer follows author, else 1.0
     *   score       = (engagement + 1) × recency × followBoost
     *   (+1 prevents brand-new reels with 0 engagement from scoring 0)
     *
     * Step 4 — sort desc, return top pageSize.
     */
    public List<ReelsByDayEntity> forYouReels(UUID viewerId, int pageSize) {
        List<ReelsByDayEntity> candidates = collectCandidates();
        if (candidates.isEmpty()) return List.of();

        Map<UUID, PostCounterEntity> counters = bulkCounters(candidates);

        Set<UUID> followingSet = viewerId != null
                ? Set.copyOf(followingIdsCache.getFilteredFollowingIds(viewerId))
                : Set.of();

        Instant now = Instant.now();
        return candidates.stream()
                .sorted(Comparator.comparingDouble(
                        (ReelsByDayEntity r) -> score(r, counters.get(r.getPostId()), followingSet, now))
                        .reversed())
                .limit(pageSize)
                .collect(Collectors.toList());
    }

    private List<ReelsByDayEntity> collectCandidates() {
        List<ReelsByDayEntity> all = new ArrayList<>(FOR_YOU_POOL);
        LocalDate today  = LocalDate.now(ZoneOffset.UTC);
        int       perDay = Math.max(1, FOR_YOU_POOL / FOR_YOU_DAYS);
        for (int d = 0; d < FOR_YOU_DAYS && all.size() < FOR_YOU_POOL; d++) {
            String bucket = today.minusDays(d).toString();
            try {
                all.addAll(reelsByDayRepo.firstPage(bucket, perDay));
            } catch (Exception e) {
                log.debug("[REEL-FYP] bucket {} unavailable: {}", bucket, e.getMessage());
            }
        }
        return all;
    }

    private Map<UUID, PostCounterEntity> bulkCounters(List<ReelsByDayEntity> reels) {
        Set<UUID> ids = reels.stream()
                .map(ReelsByDayEntity::getPostId)
                .collect(Collectors.toSet());
        Map<UUID, PostCounterEntity> out = new HashMap<>(ids.size());
        try {
            postCounterRepo.findAllByPostIdIn(ids).forEach(c -> out.put(c.getPostId(), c));
        } catch (Exception e) {
            log.debug("[REEL-FYP] counter bulk-load failed: {}", e.getMessage());
        }
        return out;
    }

    private static double score(ReelsByDayEntity reel,
                                PostCounterEntity c,
                                Set<UUID> followingSet,
                                Instant now) {
        long reactions = c != null && c.getReactionCount() != null ? c.getReactionCount() : 0L;
        long comments  = c != null && c.getCommentCount()  != null ? c.getCommentCount()  : 0L;
        long views     = c != null && c.getViewCount()     != null ? c.getViewCount()      : 0L;

        double engagement = reactions * 3.0 + comments * 2.0 + views;

        double ageHours = (double)(now.toEpochMilli() - reel.getCreatedAt().toEpochMilli())
                          / 3_600_000.0;
        double recency  = Math.exp(-ageHours / 48.0);   // 48-hour half-life

        double boost = (reel.getAuthorId() != null && followingSet.contains(reel.getAuthorId()))
                       ? 1.5 : 1.0;

        return (engagement + 1.0) * recency * boost;
    }
}
