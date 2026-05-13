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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                Counter Reconciliation Worker                             │
 * │                                                                          │
 * │  Two stages, run in order:                                               │
 * │                                                                          │
 * │  1. BULK DB RECONCILE — one atomic SQL per (entity-kind, counter-field). │
 * │     Each statement rewrites the denormalised column from the actual      │
 * │     source-of-truth row count, e.g.                                      │
 * │                                                                          │
 * │       UPDATE posts SET reaction_count =                                  │
 * │         (SELECT COUNT(*) FROM post_reactions WHERE post_id = posts.id);  │
 * │                                                                          │
 * │     The COUNT subquery runs at the same point in time as the UPDATE,     │
 * │     so a concurrent user reaction either lands in this statement OR in   │
 * │     the user's own atomic +1 — never both, never neither.                │
 * │                                                                          │
 * │  2. REDIS REHYDRATE — for every cached Redis hash under each kind's      │
 * │     prefix, read the (now-correct) DB column and HMSET it back into      │
 * │     Redis. Hash keys whose DB row has gone away are evicted.             │
 * │                                                                          │
 * │  Trigger:                                                                │
 * │   • Spring startup (ApplicationReadyEvent)   — one-shot repair of any    │
 * │     existing drift from the legacy non-atomic code paths.                │
 * │   • Every 6 hours after that — safety net for any future race we miss.  │
 * │                                                                          │
 * │  Counters with no source-of-truth table (viewCount, shareCount,          │
 * │  citationCount) are NOT reconciled — they're write-only counters; their │
 * │  Redis cache is rebuilt straight from the DB column in stage 2.          │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterReconciler {

    private static final long INTERVAL_MS = 6L * 60 * 60 * 1000;   // 6 hours
    private static final long INITIAL_DELAY_MS = 6L * 60 * 60 * 1000; // first scheduled run = 6h after startup
                                                                       // (boot-time run is handled by the
                                                                       // ApplicationReadyEvent listener below)

    private final StringRedisTemplate redis;
    private final PostRepository postRepo;
    private final PostCommentRepository postCommentRepo;
    private final ResearchRepository researchRepo;
    private final ResearchCommentRepository researchCommentRepo;
    private final QuestionRepository questionRepo;
    private final QuestionAnswerRepository answerRepo;
    private final CounterCache cache;
    private final PlatformTransactionManager txManager;
    private TransactionTemplate tx;

    @PostConstruct
    void init() {
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Repair any drift from the legacy non-atomic code paths the first time
     * this build runs. Single-shot at boot — subsequent periodic runs are
     * driven by {@link #reconcileAll()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("[RECONCILE] Startup pass beginning — repairing any drift from legacy paths");
        try {
            reconcileAll();
        } catch (Exception ex) {
            log.warn("[RECONCILE] Startup pass failed (will retry on schedule): {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void reconcileAll() {
        long started = System.currentTimeMillis();

        // Stage 1: rebuild the denormalised DB columns from row counts.
        int[] db = reconcileDbColumns();

        // Stage 2: rehydrate Redis from the now-correct DB columns.
        int posts          = reconcileKind(CounterCache.Kind.POST,            this::postCounters);
        int postComments   = reconcileKind(CounterCache.Kind.POST_COMMENT,    this::postCommentCounters);
        int researches     = reconcileKind(CounterCache.Kind.RESEARCH,        this::researchCounters);
        int rComments      = reconcileKind(CounterCache.Kind.RESEARCH_COMMENT, this::researchCommentCounters);
        int questions      = reconcileKind(CounterCache.Kind.QUESTION,        this::questionCounters);
        int answers        = reconcileKind(CounterCache.Kind.ANSWER,          this::answerCounters);

        log.info("[RECONCILE] {} ms — db:[posts.rx={} posts.cm={} posts.sv={} "
                + "pcomments.rx={} pcomments.rp={} "
                + "researches.rx={} researches.cm={} researches.sv={} researches.dl={} "
                + "rcomments.lk={} rcomments.rp={} "
                + "questions.an={} answers.rx={} answers.rp={} answers.bv={}] "
                + "redis:[posts={} postComments={} researches={} researchComments={} questions={} answers={}]",
                System.currentTimeMillis() - started,
                db[0], db[1], db[2], db[3], db[4], db[5], db[6], db[7], db[8],
                db[9], db[10], db[11], db[12], db[13], db[14],
                posts, postComments, researches, rComments, questions, answers);
    }

    /**
     * Stage 1 — rewrite every denormalised counter column from its
     * source-of-truth row count. Each call wrapped in its own transaction so
     * one slow statement doesn't hold locks across the whole sweep, and so a
     * later failure doesn't roll back the earlier repairs.
     */
    private int[] reconcileDbColumns() {
        return new int[] {
                inTx(postRepo::bulkReconcileReactionCount),
                inTx(postRepo::bulkReconcileCommentCount),
                inTx(postRepo::bulkReconcileSaveCount),
                inTx(postCommentRepo::bulkReconcileReactionCount),
                inTx(postCommentRepo::bulkReconcileReplyCount),
                inTx(researchRepo::bulkReconcileReactionCount),
                inTx(researchRepo::bulkReconcileCommentCount),
                inTx(researchRepo::bulkReconcileSaveCount),
                inTx(researchRepo::bulkReconcileDownloadCount),
                inTx(researchCommentRepo::bulkReconcileLikeCount),
                inTx(researchCommentRepo::bulkReconcileReplyCount),
                inTx(questionRepo::bulkReconcileAnswerCount),
                inTx(answerRepo::bulkReconcileReactionCount),
                inTx(answerRepo::bulkReconcileReplyCount),
                inTx(answerRepo::bulkReconcileBestAnswerVoteCount),
        };
    }

    /** Run a {@link java.util.function.IntSupplier} in its own write tx; swallow & log on failure. */
    private int inTx(java.util.function.IntSupplier action) {
        try {
            Integer result = tx.execute(status -> action.getAsInt());
            return result != null ? result : 0;
        } catch (Exception ex) {
            log.warn("[RECONCILE] bulk SQL failed: {}", ex.getMessage());
            return -1;
        }
    }

    /**
     * Stage 2 — SCAN every Redis hash under the given kind's prefix, look the
     * entity up in the DB, and write the authoritative counter set back.
     * Missing entities (deleted rows) have their cache hash dropped.
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
