package ak.dev.irc.app.post.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostVisibility;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │              Personalised Feed Ranking Service                           │
 * │                                                                          │
 * │   score = (0.40 × engagement)                                           │
 * │         + (0.30 × recency)                                              │
 * │         + (0.20 × relationship)                                         │
 * │         + (0.10 × diversity)                                            │
 * │                                                                          │
 * │   engagement   = log1p(reactions + 2·comments + 3·shares + views/20)    │
 * │   recency      = 1 / (1 + hours_since_post / 24)                        │
 * │   relationship = 1.0 if you follow the author, else 0.4                 │
 * │   diversity    = small noise to prevent identical-twin posts dominating │
 * │                                                                          │
 * │   The candidate pool is the most recent N posts visible to the viewer   │
 * │   (block-filtered). Score-and-sort is done in-process — sub-millisecond │
 * │   for ≤500 candidates. The result is cached in Redis for 60 s per       │
 * │   viewer so subsequent paginated pulls don't re-rank.                   │
 * │                                                                          │
 * │   Coefficients live as constants so they can be tuned without redeploys │
 * │   once a feature-flag service exists.                                   │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedRankingService {

    private static final double W_ENGAGEMENT  = 0.40;
    private static final double W_RECENCY     = 0.30;
    private static final double W_RELATIONSHIP = 0.20;
    private static final double W_DIVERSITY   = 0.10;

    private static final int    CANDIDATE_POOL = 500;
    private static final Duration CACHE_TTL    = Duration.ofSeconds(60);

    private final PostRepository postRepository;
    private final PostMapper postMapper;
    private final SocialGuard socialGuard;
    private final FollowingIdsCache followingIdsCache;
    private final CounterCache counterCache;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Top-N ranked feed for the given viewer.
     *
     * @param viewerId   null for anonymous (no relationship boost)
     * @param limit      max items to return (capped at 100)
     */
    @Transactional(readOnly = true)
    public List<PostResponse> rankedFeed(UUID viewerId, int limit) {
        int safe = Math.max(1, Math.min(limit, 100));

        // Per-viewer cache so two paginated calls don't re-rank from scratch.
        String cacheKey = "feed:rank:" + (viewerId != null ? viewerId.toString() : "anon") + ":" + safe;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, PostResponse.class));
            }
        } catch (Exception ignored) { /* cache miss is OK */ }

        // 1) Resolve viewer relationships up-front so we can include the
        //    FOLLOWERS_ONLY posts the viewer is entitled to see.
        Set<UUID> followed = viewerId != null
                ? new HashSet<>(followingIdsCache.getFilteredFollowingIds(viewerId))
                : Collections.emptySet();
        List<UUID> blocked = viewerId != null
                ? socialGuard.findRelatedBlockedIds(viewerId)
                : Collections.emptyList();

        // 2) Candidate pool — public posts + follower-only posts by followed
        //    authors, block-filtered. Falls back to the bare public feed when
        //    the viewer follows nobody (no follower-only content to include).
        List<Post> candidates;
        var page = PageRequest.of(0, CANDIDATE_POOL);
        if (viewerId == null || followed.isEmpty()) {
            candidates = blocked.isEmpty()
                    ? postRepository
                            .findByStatusAndVisibilityOrderByCreatedAtDesc(
                                    PostStatus.PUBLISHED, PostVisibility.PUBLIC, page)
                            .getContent()
                    : postRepository.findPublicFeedExcluding(blocked, page).getContent();
        } else {
            List<UUID> followedList = new ArrayList<>(followed);
            candidates = blocked.isEmpty()
                    ? postRepository.findForYouCandidates(followedList, page)
                    : postRepository.findForYouCandidatesExcluding(followedList, blocked, page);
        }
        if (candidates.isEmpty()) return Collections.emptyList();

        // 3) Score each candidate.
        LocalDateTime now = LocalDateTime.now();
        Random jitter = new Random(viewerId != null ? viewerId.getLeastSignificantBits() : 0L);
        List<Scored> scored = candidates.stream()
                .map(p -> new Scored(p, score(p, now, followed, jitter)))
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
                .limit(safe)
                .toList();

        List<PostResponse> result = scored.stream()
                .map(s -> postMapper.toResponse(s.post))
                .toList();

        // 4) Cache the page so a second pull within 60 s is one Redis read.
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), CACHE_TTL);
        } catch (Exception ignored) {}

        return result;
    }

    private double score(Post p, LocalDateTime now, Set<UUID> followed, Random jitter) {
        // Counter values: prefer Redis (write-through-fresh), fall back to entity.
        Map<String, Long> c = counterCache.get(CounterCache.Kind.POST, p.getId());
        long rx = c.getOrDefault(CounterCache.F_REACTIONS, nz(p.getReactionCount()));
        long cm = c.getOrDefault(CounterCache.F_COMMENTS,  nz(p.getCommentCount()));
        long sh = c.getOrDefault(CounterCache.F_SHARES,    nz(p.getShareCount()));
        long vw = c.getOrDefault(CounterCache.F_VIEWS,     nz(p.getViewCount()));

        // Engagement: log1p so a viral post doesn't crush everything else.
        double engagement = Math.log1p(rx + 2.0 * cm + 3.0 * sh + vw / 20.0);
        engagement = Math.min(1.0, engagement / 8.0);   // squash to [0,1]

        // Recency: half-life ~24 h.
        long hoursOld = Duration.between(p.getCreatedAt(), now).toHours();
        double recency = 1.0 / (1.0 + hoursOld / 24.0);

        // Relationship: full weight for followed authors, baseline for the rest.
        double relationship = followed.contains(p.getAuthor().getId()) ? 1.0 : 0.4;

        // Diversity: small per-viewer jitter so two identical scores aren't always
        // in the same order across users. Deterministic per (viewer, post).
        long seed = p.getId().getLeastSignificantBits();
        double diversity = (Math.abs(seed % 1000) / 1000.0) * 0.5 + 0.5 * jitter.nextDouble();

        return W_ENGAGEMENT * engagement
             + W_RECENCY * recency
             + W_RELATIONSHIP * relationship
             + W_DIVERSITY * diversity;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private record Scored(Post post, double score) {}
}
