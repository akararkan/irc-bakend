package ak.dev.irc.app.common.cache;

import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.repository.PostCommentRepository;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchComment;
import ak.dev.irc.app.research.repository.ResearchCommentRepository;
import ak.dev.irc.app.research.repository.ResearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                Counter Reconciliation Worker                             │
 * │                                                                          │
 * │  Periodically walks every Redis counter hash and rewrites its fields     │
 * │  from the authoritative DB row. Self-heals against any future bug that   │
 * │  bumps the DB without writing through to Redis (we've hit two such       │
 * │  bugs already — this worker makes the next one invisible to users).      │
 * │                                                                          │
 * │  Strategy: SCAN by prefix per kind, then bulk read DB by id and HMSET.   │
 * │  Bounded by Redis key count, not by entity count (cold/evicted rows are  │
 * │  skipped because their cache TTL has expired). Sub-second on hundreds of │
 * │  thousands of hot entities; tunable via fixedDelay.                      │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterReconciler {

    private static final long INTERVAL_MS = 6L * 60 * 60 * 1000;   // 6 hours
    private static final long INITIAL_DELAY_MS = 5L * 60 * 1000;   // 5 min after boot

    private final StringRedisTemplate redis;
    private final PostRepository postRepo;
    private final PostCommentRepository postCommentRepo;
    private final ResearchRepository researchRepo;
    private final ResearchCommentRepository researchCommentRepo;
    private final QuestionRepository questionRepo;
    private final QuestionAnswerRepository answerRepo;
    private final CounterCache cache;

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void reconcileAll() {
        long started = System.currentTimeMillis();
        int posts          = reconcileKind(CounterCache.Kind.POST,            this::postCounters);
        int postComments   = reconcileKind(CounterCache.Kind.POST_COMMENT,    this::postCommentCounters);
        int researches     = reconcileKind(CounterCache.Kind.RESEARCH,        this::researchCounters);
        int rComments      = reconcileKind(CounterCache.Kind.RESEARCH_COMMENT, this::researchCommentCounters);
        int questions      = reconcileKind(CounterCache.Kind.QUESTION,        this::questionCounters);
        int answers        = reconcileKind(CounterCache.Kind.ANSWER,          this::answerCounters);

        log.info("[RECONCILE] {} ms — posts={} postComments={} researches={} researchComments={} questions={} answers={}",
                System.currentTimeMillis() - started,
                posts, postComments, researches, rComments, questions, answers);
    }

    /**
     * SCAN every Redis hash under the given kind's prefix, look the entity up
     * in the DB, and write the authoritative counter set back. Missing entities
     * (deleted rows) have their cache hash dropped.
     */
    private int reconcileKind(CounterCache.Kind kind,
                              java.util.function.Function<UUID, Map<String, Long>> fetcher) {
        int touched = 0;
        ScanOptions opts = ScanOptions.scanOptions().match(kind.prefix + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(opts)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String idStr = key.substring(kind.prefix.length());
                try {
                    UUID id = UUID.fromString(idStr);
                    Map<String, Long> counters = fetcher.apply(id);
                    if (counters == null) {
                        // Row gone — drop the orphan hash so memory doesn't leak.
                        cache.invalidate(kind, id);
                    } else if (!counters.isEmpty()) {
                        cache.setAll(kind, id, counters);
                    }
                    touched++;
                } catch (IllegalArgumentException ex) {
                    // Malformed id — drop the key.
                    redis.delete(key);
                }
            }
        } catch (Exception ex) {
            log.warn("[RECONCILE] {} scan failed: {}", kind, ex.getMessage());
        }
        return touched;
    }

    // ── Per-kind DB → counter-map adapters ────────────────────────────────

    private Map<String, Long> postCounters(UUID id) {
        return postRepo.findById(id).map(p -> {
            Map<String, Long> m = new HashMap<>(5);
            m.put(CounterCache.F_REACTIONS, nz(p.getReactionCount()));
            m.put(CounterCache.F_COMMENTS,  nz(p.getCommentCount()));
            m.put(CounterCache.F_SHARES,    nz(p.getShareCount()));
            m.put(CounterCache.F_VIEWS,     nz(p.getViewCount()));
            m.put(CounterCache.F_SAVES,     nz(p.getSaveCount()));
            return m;
        }).orElse(null);
    }

    private Map<String, Long> postCommentCounters(UUID id) {
        return postCommentRepo.findById(id).map(c -> {
            Map<String, Long> m = new HashMap<>(2);
            m.put(CounterCache.F_REACTIONS, nz(c.getReactionCount()));
            m.put(CounterCache.F_REPLIES,   nz(c.getReplyCount()));
            return m;
        }).orElse(null);
    }

    private Map<String, Long> researchCounters(UUID id) {
        return researchRepo.findById(id).map(r -> {
            Map<String, Long> m = new HashMap<>(7);
            m.put(CounterCache.F_REACTIONS, nz(r.getReactionCount()));
            m.put(CounterCache.F_COMMENTS,  nz(r.getCommentCount()));
            m.put(CounterCache.F_SHARES,    nz(r.getShareCount()));
            m.put(CounterCache.F_SAVES,     nz(r.getSaveCount()));
            m.put(CounterCache.F_VIEWS,     nz(r.getViewCount()));
            m.put(CounterCache.F_DOWNLOADS, nz(r.getDownloadCount()));
            m.put(CounterCache.F_CITATIONS, nz(r.getCitationCount()));
            return m;
        }).orElse(null);
    }

    private Map<String, Long> researchCommentCounters(UUID id) {
        return researchCommentRepo.findById(id).map(c -> {
            Map<String, Long> m = new HashMap<>(2);
            m.put(CounterCache.F_REACTIONS, nz(c.getLikeCount()));
            m.put(CounterCache.F_REPLIES,   nz(c.getReplyCount()));
            return m;
        }).orElse(null);
    }

    private Map<String, Long> questionCounters(UUID id) {
        return questionRepo.findById(id).map(q -> {
            Map<String, Long> m = new HashMap<>(2);
            m.put(CounterCache.F_ANSWERS, nz(q.getAnswerCount()));
            m.put(CounterCache.F_VIEWS,   nz(q.getViewCount()));
            return m;
        }).orElse(null);
    }

    private Map<String, Long> answerCounters(UUID id) {
        return answerRepo.findById(id).map(a -> {
            Map<String, Long> m = new HashMap<>(3);
            m.put(CounterCache.F_REACTIONS,  nz(a.getReactionCount()));
            m.put(CounterCache.F_REPLIES,    nz(a.getReplyCount()));
            m.put(CounterCache.F_BEST_VOTES, nz(a.getBestAnswerVoteCount()));
            return m;
        }).orElse(null);
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
