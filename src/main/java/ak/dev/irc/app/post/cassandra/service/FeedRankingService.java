package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.dto.FeedItemResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The home-feed ranking model — the deterministic engagement-prediction
 * stage of the multi-stage pipeline (candidate generation happens in
 * {@code HomeFeedService}; this class only scores and re-ranks).
 *
 * <p>Score formula (the classic weighted-signals model that the ML
 * rankers at Facebook/Instagram/TikTok approximate with learned weights):</p>
 *
 * <pre>
 *   E  = 3·ln(1+likes) + 4·ln(1+comments) + 5·ln(1+shares) + 4·ln(1+saves) + 0.5·ln(1+views)
 *   A  = 2·ln(1+affinity(viewer, author))          — interest graph
 *   F  = e^(−ageHours / halfLife)                  — freshness decay
 *   B  = relationship/source boost multiplier
 *   score = (1 + E + A) · F · B
 * </pre>
 *
 * <ul>
 *   <li><b>log-damping</b> keeps a viral 100k-like post from erasing
 *       everything else — engagement grows the score sub-linearly, exactly
 *       the "diminishing returns" shape ranking models learn.</li>
 *   <li><b>comment/share &gt; save &gt; like &gt; view</b> mirrors the
 *       meaningful-interaction hierarchy (a comment predicts retention far
 *       better than a passive view).</li>
 *   <li><b>freshness half-life per content type</b>: fast-moving POSTs
 *       decay on a 24h half-life, REELs (evergreen video) 48h, channel
 *       broadcasts 18h (news-like), RESEARCH 72h (long shelf-life),
 *       QUESTIONs 48h (answers take time).</li>
 *   <li><b>boosts</b>: mutual follow ×1.25 (relationship strength), own
 *       posts ×1.05 (users expect to see what they just posted), CHANNEL
 *       source ×0.9 and EXPLORE ×0.85 (injected discovery must earn its
 *       slot, not crowd out the social graph).</li>
 * </ul>
 *
 * <p>After scoring, {@link #diversify} applies the diversity re-ranking
 * stage: no author/channel occupies more than {@value #MAX_AUTHOR_RUN}
 * consecutive slots, so one prolific account can't wallpaper the page.</p>
 */
@Slf4j
@Service
public class FeedRankingService {

    // Engagement weights (per the meaningful-interaction hierarchy).
    static final double W_LIKE    = 3.0;
    static final double W_COMMENT = 4.0;
    static final double W_SHARE   = 5.0;
    static final double W_SAVE    = 4.0;
    static final double W_VIEW    = 0.5;
    /** Affinity multiplier — the interest-graph term. */
    static final double W_AFFINITY = 2.0;

    // Freshness half-lives, hours.
    static final double HALF_LIFE_POST     = 24.0;
    static final double HALF_LIFE_REEL     = 48.0;
    static final double HALF_LIFE_CHANNEL  = 18.0;
    static final double HALF_LIFE_RESEARCH = 72.0;
    static final double HALF_LIFE_QUESTION = 48.0;

    // Boost multipliers.
    static final double BOOST_MUTUAL  = 1.25;
    static final double BOOST_SELF    = 1.05;
    static final double DAMP_CHANNEL  = 0.90;
    static final double DAMP_EXPLORE  = 0.85;

    /** Max consecutive items from the same author / channel. */
    static final int MAX_AUTHOR_RUN = 2;

    /**
     * The complete tunable-knob set. {@link #DEFAULTS} mirrors the compile-time
     * constants; {@code FeedTuningService} materialises a runtime override row
     * into one of these (staged by rollout percent) and threads it through
     * {@code HomeFeedService} → {@link #score} / {@link #diversify}.
     */
    public record Knobs(double wLike, double wComment, double wShare, double wSave,
                        double wView, double wAffinity,
                        double halfLifePost, double halfLifeReel, double halfLifeChannel,
                        double halfLifeResearch, double halfLifeQuestion,
                        double boostMutual, double boostSelf,
                        double dampChannel, double dampExplore,
                        int maxAuthorRun) {

        public static final Knobs DEFAULTS = new Knobs(
                W_LIKE, W_COMMENT, W_SHARE, W_SAVE, W_VIEW, W_AFFINITY,
                HALF_LIFE_POST, HALF_LIFE_REEL, HALF_LIFE_CHANNEL,
                HALF_LIFE_RESEARCH, HALF_LIFE_QUESTION,
                BOOST_MUTUAL, BOOST_SELF, DAMP_CHANNEL, DAMP_EXPLORE,
                MAX_AUTHOR_RUN);
    }

    /**
     * Read-only knob registry for the admin observability surface
     * (search-feed-trending.md §2.3.1). Every value here is a compile-time
     * constant — changing one is a recompile + redeploy, and the dashboard
     * says so instead of pretending to offer runtime tuning.
     */
    public static java.util.Map<String, Object> knobRegistry() {
        java.util.Map<String, Object> knobs = new java.util.LinkedHashMap<>();
        knobs.put("formula", "score = (1 + E + A) · F · B");
        knobs.put("W_LIKE", W_LIKE);
        knobs.put("W_COMMENT", W_COMMENT);
        knobs.put("W_SHARE", W_SHARE);
        knobs.put("W_SAVE", W_SAVE);
        knobs.put("W_VIEW", W_VIEW);
        knobs.put("W_AFFINITY", W_AFFINITY);
        knobs.put("HALF_LIFE_POST_H", HALF_LIFE_POST);
        knobs.put("HALF_LIFE_REEL_H", HALF_LIFE_REEL);
        knobs.put("HALF_LIFE_CHANNEL_H", HALF_LIFE_CHANNEL);
        knobs.put("HALF_LIFE_RESEARCH_H", HALF_LIFE_RESEARCH);
        knobs.put("HALF_LIFE_QUESTION_H", HALF_LIFE_QUESTION);
        knobs.put("BOOST_MUTUAL", BOOST_MUTUAL);
        knobs.put("BOOST_SELF", BOOST_SELF);
        knobs.put("DAMP_CHANNEL", DAMP_CHANNEL);
        knobs.put("DAMP_EXPLORE", DAMP_EXPLORE);
        knobs.put("MAX_AUTHOR_RUN", MAX_AUTHOR_RUN);
        knobs.put("tuning", "these are the compile-time DEFAULTS; a runtime override row "
                + "(feed_ranking_config via FeedTuningService, staged by rolloutPercent) "
                + "can supersede them — see GET /api/v1/admin/feed/config");
        return knobs;
    }

    /** Everything the scorer needs about the viewer, bulk-loaded per page. */
    public record RankSignals(
            UUID viewerId,
            Map<UUID, Long> affinityByAuthor,
            Set<UUID> mutualFollowAuthors
    ) {
        public static RankSignals empty(UUID viewerId) {
            return new RankSignals(viewerId, Map.of(), Set.of());
        }
    }

    /** A candidate with its provenance and computed score. */
    public record Scored(FeedItemResponse item, String source, double score) {}

    /** Score one hydrated candidate under the compile-time defaults. */
    public Scored score(FeedItemResponse item, String source, RankSignals signals, Instant now) {
        return score(item, source, signals, now, Knobs.DEFAULTS);
    }

    /** Score one hydrated candidate under an explicit knob set. */
    public Scored score(FeedItemResponse item, String source, RankSignals signals,
                        Instant now, Knobs k) {
        double engagement =
                k.wLike()    * Math.log1p(item.reactionCount()) +
                k.wComment() * Math.log1p(item.commentCount())  +
                k.wShare()   * Math.log1p(item.shareCount())    +
                k.wSave()    * Math.log1p(item.saveCount())     +
                k.wView()    * Math.log1p(item.viewCount());

        double affinity = 0.0;
        UUID author = item.authorId();
        if (author != null) {
            long interactions = signals.affinityByAuthor().getOrDefault(author, 0L);
            affinity = k.wAffinity() * Math.log1p(interactions);
        }

        double freshness = freshness(item, now, k);

        double boost = 1.0;
        if (author != null && author.equals(signals.viewerId()))            boost *= k.boostSelf();
        else if (author != null && signals.mutualFollowAuthors().contains(author)) boost *= k.boostMutual();
        if (HomeFeedSources.CHANNEL.equals(source)) boost *= k.dampChannel();
        if (HomeFeedSources.EXPLORE.equals(source)) boost *= k.dampExplore();

        return new Scored(item, source, (1.0 + engagement + affinity) * freshness * boost);
    }

    private static double freshness(FeedItemResponse item, Instant now, Knobs k) {
        Instant createdAt = item.createdAt();
        if (createdAt == null) return 0.5;   // undated — middle of the road
        double ageHours = Math.max(0.0,
                (now.toEpochMilli() - createdAt.toEpochMilli()) / 3_600_000.0);
        return Math.exp(-ageHours / halfLifeOf(item, k));
    }

    private static double halfLifeOf(FeedItemResponse item, Knobs k) {
        String entityType = item.entityType();
        if ("CHANNEL_POST".equals(entityType)) return k.halfLifeChannel();
        if ("RESEARCH".equals(entityType))     return k.halfLifeResearch();
        if ("QUESTION".equals(entityType))     return k.halfLifeQuestion();
        return "REEL".equals(item.postType()) ? k.halfLifeReel() : k.halfLifePost();
    }

    /**
     * Diversity re-ranking: greedy pass over the score-sorted list that
     * defers a candidate whenever its author/channel already holds the last
     * {@value #MAX_AUTHOR_RUN} emitted slots. If every remaining candidate
     * violates the cap (single-author feed), the highest-scored one is
     * emitted anyway — the cap shapes, it never drops.
     */
    public List<Scored> diversify(List<Scored> sortedByScoreDesc) {
        return diversify(sortedByScoreDesc, Knobs.DEFAULTS);
    }

    /** Diversity re-ranking under an explicit knob set. */
    public List<Scored> diversify(List<Scored> sortedByScoreDesc, Knobs k) {
        int run = Math.max(1, k.maxAuthorRun());
        if (sortedByScoreDesc.size() <= run) return sortedByScoreDesc;

        LinkedList<Scored> pool = new LinkedList<>(sortedByScoreDesc);
        List<Scored> out = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            Scored picked = null;
            for (Scored candidate : pool) {
                if (!violatesRun(out, candidate, run)) { picked = candidate; break; }
            }
            if (picked == null) picked = pool.getFirst();   // all violate — take the best
            pool.remove(picked);
            out.add(picked);
        }
        return out;
    }

    private static boolean violatesRun(List<Scored> emitted, Scored candidate, int run) {
        if (emitted.size() < run) return false;
        Object key = authorKey(candidate);
        if (key == null) return false;
        for (int i = emitted.size() - run; i < emitted.size(); i++) {
            if (!key.equals(authorKey(emitted.get(i)))) return false;
        }
        return true;
    }

    /** Channel posts group by channel; everything else by author. */
    private static Object authorKey(Scored s) {
        if (s.item().channel() != null) return s.item().channel().id();
        return s.item().authorId();
    }
}
