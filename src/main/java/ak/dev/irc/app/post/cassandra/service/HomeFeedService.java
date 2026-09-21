package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.service.ChannelFeedCandidateService;
import ak.dev.irc.app.chat.service.ChannelFeedCandidateService.ChannelPostCandidate;
import ak.dev.irc.app.chat.service.LiveStreamService;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.service.FeedRankingService.RankSignals;
import ak.dev.irc.app.post.cassandra.service.FeedRankingService.Scored;
import ak.dev.irc.app.post.dto.FeedItemResponse;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The ranked home feed — multi-stage recommendation pipeline over the
 * existing fanout-on-write timeline, per the modern-feed architecture
 * (candidate generation → safety filtering → scoring → diversity
 * re-ranking → delivery).
 *
 * <h3>Stage 1 — candidate generation (parallel, bounded)</h3>
 * <ul>
 *   <li><b>Social graph</b>: the viewer's {@code feed_by_user} window
 *       (posts + research + questions from followed accounts, own posts
 *       merged) — the same chronological keyset window as before, so
 *       cursor pagination stays stateless and loss-free.</li>
 *   <li><b>Channels</b> (first page only): fresh posts from subscribed
 *       broadcast channels via {@link ChannelFeedCandidateService}.</li>
 *   <li><b>Exploration</b> (first page only): the discovery slice — trending
 *       reels PLUS the newest public posts (via the ES discovery pool), all
 *       from authors the viewer does NOT follow. Normally ~a quarter of the
 *       page; tops the page up whenever the social graph can't fill it, and
 *       expands to the whole page for cold-start users — a new account sees
 *       a real feed before following anyone, like any social network.</li>
 *   <li><b>Live rail</b> (first page only): streams from followed hosts,
 *       topped up with the most-watched public streams — returned beside
 *       the items, Instagram-stories-style, not competing for item slots.</li>
 * </ul>
 *
 * <h3>Stage 2 — safety filtering</h3>
 * Blocked-author items are dropped at read time (fanout rows written
 * before a block, and explore/global candidates, can otherwise leak
 * through). Deleted posts are already dropped by hydration.
 *
 * <h3>Stage 3 — scoring, Stage 4 — diversity</h3>
 * Delegated to {@link FeedRankingService}: engagement (log-damped) +
 * affinity (interest graph) + freshness half-life + relationship boosts,
 * then a max-2-consecutive-per-author re-rank.
 *
 * <h3>Pagination contract</h3>
 * Ranking happens strictly <i>within</i> the chronological window, and
 * {@code nextCursor} is computed from the RAW window (its oldest row), so
 * every timeline row is delivered exactly once regardless of rank order.
 * Legacy clients that derive the cursor from the last array element get
 * {@link #pinOldestTimelineLast} applied by the controller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeFeedService {

    private static final int LIVE_RAIL_MAX      = 10;
    private static final int CHANNEL_ITEMS_MAX  = 8;
    private static final int EXPLORE_MIN        = 1;
    /** Normal ceiling ~a quarter of a 20-item page — visible discovery without
     *  drowning the social graph. (The quota separately expands to fill
     *  whatever part of the page the graph left empty — see exploreItems.) */
    private static final int EXPLORE_MAX        = 6;
    private static final int EXPLORE_POOL_MULT  = 3;

    private final FeedTimelineService         feedTimeline;
    private final PostHydrator                hydrator;
    private final ReelFeedService             reelFeedService;
    private final FeedRankingService          ranking;
    private final AuthorAffinityService       affinityService;
    private final ChannelFeedCandidateService channelCandidateService;
    private final LiveStreamService           liveStreamService;
    private final FollowingIdsCache           followingIdsCache;
    private final UserBlockRepository         blockRepo;
    private final UserFollowRepository        followRepo;
    private final ThreadPoolTaskExecutor      taskExecutor;
    private final ak.dev.irc.app.admin.feed.FeedTuningService feedTuning;
    private final ak.dev.irc.app.post.search.service.PostDiscoveryService discoveryService;

    /** Ranked page + live rail + the stateless continuation cursor. */
    public record RankedFeed(List<FeedItemResponse> items,
                             List<LiveStreamResponse> liveNow,
                             Instant nextCursor) {}

    public RankedFeed rankedHomeFeed(UUID viewer, int pageSize, Instant cursor) {
        return rankedHomeFeed(viewer, pageSize, cursor, feedTuning.effective(viewer));
    }

    /**
     * Knob-explicit variant — the admin preview endpoint shadow-scores a real
     * user's feed under proposed knobs without touching the stored config.
     */
    public RankedFeed rankedHomeFeed(UUID viewer, int pageSize, Instant cursor,
                                     ak.dev.irc.app.post.cassandra.service.FeedRankingService.Knobs knobs) {
        boolean firstPage = cursor == null;
        Instant now = Instant.now();

        // ── Stage 1: candidate generation (sources fetched in parallel; any
        //    source failing degrades to empty rather than failing the feed) ──
        CompletableFuture<List<FeedByUserEntity>> timelineF = CompletableFuture.supplyAsync(
                () -> firstPage ? feedTimeline.homeFeed(viewer, pageSize)
                                : feedTimeline.homeFeedAfter(viewer, cursor, pageSize),
                taskExecutor);
        CompletableFuture<List<ChannelPostCandidate>> channelF = firstPage
                ? CompletableFuture.supplyAsync(() -> channelCandidateService.recentChannelPosts(viewer), taskExecutor)
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<LiveStreamResponse>> liveF = firstPage
                ? CompletableFuture.supplyAsync(() -> liveRail(viewer), taskExecutor)
                : CompletableFuture.completedFuture(List.of());

        List<FeedByUserEntity> timelineRows = joinOrEmpty(timelineF, "timeline");
        List<ChannelPostCandidate> channelCandidates = joinOrEmpty(channelF, "channels");
        List<LiveStreamResponse> liveNow = joinOrEmpty(liveF, "live-rail");

        // Continuation cursor from the RAW window — rank order never leaks
        // into pagination (rows are createdAt DESC; last = oldest).
        Instant nextCursor = timelineRows.isEmpty()
                ? null
                : timelineRows.get(timelineRows.size() - 1).getCreatedAt();

        // Hydration runs on the request thread — it resolves likedByMe /
        // savedByMe through the SecurityContext, which async threads lack.
        List<FeedItemResponse> timelineItems = hydrator.hydrateHomeFeed(timelineRows);

        Set<UUID> followingSet = new HashSet<>(followingIdsCache.getFilteredFollowingIds(viewer));
        List<FeedItemResponse> exploreItems = firstPage
                ? exploreItems(viewer, pageSize, followingSet, timelineItems)
                : List.of();

        List<FeedItemResponse> channelItems = channelCandidates.stream()
                .map(HomeFeedService::toFeedItem)
                .toList();

        // ── Stage 2: safety filter — drop blocked authors at read time ──
        Set<UUID> authorIds = new LinkedHashSet<>();
        timelineItems.forEach(i -> { if (i.authorId() != null) authorIds.add(i.authorId()); });
        exploreItems.forEach(i ->  { if (i.authorId() != null) authorIds.add(i.authorId()); });
        Set<UUID> blocked = blockedAmong(viewer, authorIds);
        if (!blocked.isEmpty()) {
            timelineItems = timelineItems.stream().filter(i -> !blocked.contains(i.authorId())).toList();
            exploreItems  = exploreItems.stream().filter(i -> !blocked.contains(i.authorId())).toList();
            authorIds.removeAll(blocked);
        }

        // ── Stage 3: bulk signals + scoring ──
        Map<UUID, Long> affinity = affinityService.bulkFor(viewer, authorIds);
        Set<UUID> mutuals = mutualFollows(viewer, authorIds, followingSet);
        RankSignals signals = new RankSignals(viewer, affinity, mutuals);

        List<Scored> scored = new ArrayList<>(
                timelineItems.size() + channelItems.size() + exploreItems.size());
        for (FeedItemResponse i : timelineItems) {
            String source = viewer.equals(i.authorId()) ? HomeFeedSources.SELF : HomeFeedSources.FOLLOWING;
            scored.add(ranking.score(i, source, signals, now, knobs));
        }
        // Channel digest: score all, keep only the strongest few — a chatty
        // channel must not out-volume the social graph.
        channelItems.stream()
                .map(i -> ranking.score(i, HomeFeedSources.CHANNEL, signals, now, knobs))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(CHANNEL_ITEMS_MAX)
                .forEach(scored::add);
        for (FeedItemResponse i : exploreItems) {
            scored.add(ranking.score(i, HomeFeedSources.EXPLORE, signals, now, knobs));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        // ── Stage 4: diversity re-rank, then final assembly ──
        List<FeedItemResponse> items = ranking.diversify(scored, knobs).stream()
                .map(s -> s.item().withRanking(s.source(), s.score()))
                .toList();

        return new RankedFeed(items, liveNow, nextCursor);
    }

    /**
     * The chronological "Latest" page — the follow-graph timeline merged BY
     * TIME with the public-content discovery pool, so Latest shows strangers'
     * public posts exactly like the ranked tab does, just in strict
     * newest-first order (no scoring, no diversity pass — Latest promises
     * chronology).
     *
     * <p>Cursor contract: both sources window on {@code createdAt} strictly
     * below the cursor (timeline via {@code homeFeedAfter}, discovery via the
     * keyset ES variant), so the cursor of the MERGED page — its oldest
     * emitted row — re-enters both sources loss-free and duplicate-free.</p>
     *
     * <p>Discovery bypasses fanout, so the blocked-author filter must run
     * here explicitly; timeline rows keep the legacy behavior. Discovery
     * items are labeled {@code EXPLORE} so the UI shows its "Suggested for
     * you" chrome and inline Follow.</p>
     */
    public RankedFeed latestFeed(UUID viewer, int pageSize, Instant cursor) {
        boolean firstPage = cursor == null;
        List<FeedByUserEntity> rows = firstPage
                ? feedTimeline.homeFeed(viewer, pageSize)
                : feedTimeline.homeFeedAfter(viewer, cursor, pageSize);
        List<FeedItemResponse> timeline = hydrator.hydrateHomeFeed(rows);

        Set<UUID> followingSet = new HashSet<>(followingIdsCache.getFilteredFollowingIds(viewer));
        Set<UUID> seen = new HashSet<>();
        timeline.forEach(i -> seen.add(i.id()));

        List<UUID> discoveredIds = discoveryService
                .recentPublicPosts(viewer, pageSize * EXPLORE_POOL_MULT, cursor)
                .stream()
                .filter(d -> d.authorId() != null
                        && !d.authorId().equals(viewer)
                        && !followingSet.contains(d.authorId())
                        && !seen.contains(d.postId()))
                .map(ak.dev.irc.app.post.search.service.PostDiscoveryService.DiscoveredPost::postId)
                .toList();
        List<FeedItemResponse> discovery = hydrator.hydrateByIds(discoveredIds);

        Set<UUID> discoveryAuthors = new LinkedHashSet<>();
        discovery.forEach(i -> { if (i.authorId() != null) discoveryAuthors.add(i.authorId()); });
        Set<UUID> blocked = blockedAmong(viewer, discoveryAuthors);
        if (!blocked.isEmpty()) {
            discovery = discovery.stream().filter(i -> !blocked.contains(i.authorId())).toList();
        }

        List<FeedItemResponse> merged = new ArrayList<>(timeline.size() + discovery.size());
        merged.addAll(timeline);
        for (FeedItemResponse i : discovery) merged.add(i.withRanking(HomeFeedSources.EXPLORE, 0.0));
        merged.sort(Comparator.comparing(FeedItemResponse::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (merged.size() > pageSize) merged = new ArrayList<>(merged.subList(0, pageSize));

        Instant next = null;
        for (FeedItemResponse i : merged) { if (i.createdAt() != null) next = i.createdAt(); }
        return new RankedFeed(merged, firstPage ? liveRail(viewer) : List.of(), next);
    }

    // ── Live rail ────────────────────────────────────────────────────────────

    /**
     * "Live now" rail: followed hosts first (most recently started), filled
     * up to {@value #LIVE_RAIL_MAX} with the most-watched public streams —
     * discovery without drowning the social signal. Blocked hosts dropped.
     */
    public List<LiveStreamResponse> liveRail(UUID viewer) {
        List<LiveStreamResponse> rail = new ArrayList<>(LIVE_RAIL_MAX);
        Set<UUID> seen = new HashSet<>();
        try {
            for (LiveStreamResponse s : liveStreamService.listFollowingLive(viewer)) {
                if (rail.size() >= LIVE_RAIL_MAX) break;
                if (seen.add(s.id())) rail.add(s);
            }
            if (rail.size() < LIVE_RAIL_MAX) {
                for (LiveStreamResponse s : liveStreamService.listLive()) {
                    if (rail.size() >= LIVE_RAIL_MAX) break;
                    if (seen.add(s.id())) rail.add(s);
                }
            }
        } catch (Exception e) {
            log.debug("[HOME-FEED] live rail unavailable: {}", e.getMessage());
            return rail;
        }
        if (rail.isEmpty()) return rail;

        Set<UUID> hostIds = new HashSet<>();
        rail.forEach(s -> { if (s.hostId() != null) hostIds.add(s.hostId()); });
        Set<UUID> blockedHosts = blockedAmong(viewer, hostIds);
        if (blockedHosts.isEmpty()) return rail;
        return rail.stream().filter(s -> !blockedHosts.contains(s.hostId())).toList();
    }

    // ── Legacy array-shape compatibility ─────────────────────────────────────

    /**
     * Pre-ranking clients derive the next cursor from the LAST array
     * element's {@code createdAt}. In ranked order the last element is not
     * the oldest, which would make that derived cursor skip rows. Moving the
     * chronologically-oldest timeline item to the tail keeps those clients
     * paginating correctly at the cost of one item sitting out of rank
     * order — at the bottom of the page, where attention is lowest.
     */
    public static List<FeedItemResponse> pinOldestTimelineLast(List<FeedItemResponse> items) {
        int oldestIdx = -1;
        Instant oldest = null;
        for (int i = 0; i < items.size(); i++) {
            FeedItemResponse it = items.get(i);
            boolean timeline = HomeFeedSources.FOLLOWING.equals(it.source())
                            || HomeFeedSources.SELF.equals(it.source());
            if (!timeline || it.createdAt() == null) continue;
            if (oldest == null || it.createdAt().isBefore(oldest)) {
                oldest = it.createdAt();
                oldestIdx = i;
            }
        }
        if (oldestIdx < 0 || oldestIdx == items.size() - 1) return items;
        List<FeedItemResponse> out = new ArrayList<>(items);
        out.add(out.remove(oldestIdx));
        return out;
    }

    // ── Candidate helpers ────────────────────────────────────────────────────

    /**
     * Exploration slice — public content from authors the viewer does NOT
     * follow, so a feed acts like a social network's rather than a private
     * timeline. Two pools, blended:
     * <ol>
     *   <li>engagement-ranked recent reels ({@link ReelFeedService}) —
     *       the original interest-graph slice;</li>
     *   <li>the newest public posts of ANY type via the ES discovery pool
     *       ({@link ak.dev.irc.app.post.search.service.PostDiscoveryService})
     *       — this is what puts strangers' ordinary posts in front of a
     *       viewer before they follow anyone.</li>
     * </ol>
     * Quota: normally {@value #EXPLORE_MIN}..{@value #EXPLORE_MAX} items,
     * but it EXPANDS to whatever part of the page the social graph left
     * unfilled — a cold-start viewer gets a full page of discovery, a
     * sparse-graph viewer gets a full page rather than two lonely rows.
     * Both pools drop self / followed authors / rows already on the page;
     * the block filter and the EXPLORE score-damp run downstream.
     */
    private List<FeedItemResponse> exploreItems(UUID viewer, int pageSize,
                                                Set<UUID> followingSet,
                                                List<FeedItemResponse> timelineItems) {
        int normal = Math.min(EXPLORE_MAX, Math.max(EXPLORE_MIN, pageSize / 4));
        int quota = Math.max(normal, pageSize - timelineItems.size());
        try {
            Set<UUID> seen = new HashSet<>();
            timelineItems.forEach(i -> seen.add(i.id()));

            List<FeedItemResponse> out = new ArrayList<>(quota);

            List<ReelsByDayEntity> reelPool = reelFeedService.forYouReels(viewer, quota * EXPLORE_POOL_MULT)
                    .stream()
                    .filter(r -> r.getAuthorId() != null
                            && !r.getAuthorId().equals(viewer)
                            && !followingSet.contains(r.getAuthorId())
                            && !seen.contains(r.getPostId()))
                    .toList();
            for (FeedItemResponse i : hydrator.hydrateReels(reelPool)) {
                if (out.size() >= quota) break;
                if (seen.add(i.id())) out.add(i);
            }

            if (out.size() < quota) {
                List<UUID> discovered = discoveryService
                        .recentPublicPosts(viewer, quota * EXPLORE_POOL_MULT)
                        .stream()
                        .filter(d -> d.authorId() != null
                                && !d.authorId().equals(viewer)
                                && !followingSet.contains(d.authorId())
                                && !seen.contains(d.postId()))
                        .map(ak.dev.irc.app.post.search.service.PostDiscoveryService.DiscoveredPost::postId)
                        .toList();
                for (FeedItemResponse i : hydrator.hydrateByIds(discovered)) {
                    if (out.size() >= quota) break;
                    if (seen.add(i.id())) out.add(i);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("[HOME-FEED] explore slice unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Channel post → feed item. The synthetic UUID ({@code msb=0,
     * lsb=snowflake}) exists only for client-side list keying; real
     * addressing uses {@code channel.id} + {@code channelPostId}. Counter
     * remap: views→viewCount, forwards→shareCount, comments→commentCount.
     */
    private static FeedItemResponse toFeedItem(ChannelPostCandidate c) {
        return new FeedItemResponse(
                new UUID(0L, c.messageId()),
                null,                       // authored by the channel, not a user
                null,
                "CHANNEL_POST",
                "CHANNEL_POST",
                c.preview(),
                c.mediaUrl(),
                null,
                0L,
                c.comments(),
                c.views(),
                0L,
                c.forwards(),
                false, false,
                c.createdAt(),
                HomeFeedSources.CHANNEL,
                null,
                c.channel(),
                Long.toString(c.messageId()),
                null);
    }

    private Set<UUID> blockedAmong(UUID viewer, Set<UUID> candidateIds) {
        if (candidateIds.isEmpty()) return Set.of();
        try {
            return new HashSet<>(blockRepo.findBlockedAmong(viewer, candidateIds));
        } catch (Exception e) {
            log.debug("[HOME-FEED] block filter unavailable: {}", e.getMessage());
            return Set.of();
        }
    }

    /** Mutual = the author follows the viewer AND the viewer follows the author. */
    private Set<UUID> mutualFollows(UUID viewer, Set<UUID> authorIds, Set<UUID> followingSet) {
        if (authorIds.isEmpty()) return Set.of();
        try {
            Set<UUID> followsMe = new HashSet<>(followRepo.findFollowerIdsAmong(viewer, authorIds));
            followsMe.retainAll(followingSet);
            return followsMe;
        } catch (Exception e) {
            log.debug("[HOME-FEED] mutual-follow lookup unavailable: {}", e.getMessage());
            return Set.of();
        }
    }

    private static <T> List<T> joinOrEmpty(CompletableFuture<List<T>> future, String label) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("[HOME-FEED] {} candidate source failed: {}", label, e.getMessage());
            return List.of();
        }
    }
}
