package ak.dev.irc.app.admin.feed;

import ak.dev.irc.app.post.cassandra.service.FeedRankingService.Knobs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the effective ranking knobs per viewer: the stored override row
 * (when enabled) applied to the compile-time defaults, gated by a sticky
 * per-user rollout bucket. The row is cached for 30 seconds so the feed hot
 * path costs zero extra queries between refreshes; a config PATCH is live
 * platform-wide within that window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTuningService {

    private static final long CACHE_MS = 30_000;

    private final FeedRankingConfigRepository repository;

    private volatile FeedRankingConfig cached;
    private volatile long cachedAt;

    /** The knobs this viewer's feed should rank with. Fail-open to defaults. */
    public Knobs effective(UUID viewerId) {
        try {
            FeedRankingConfig cfg = current();
            if (cfg == null || !cfg.isEnabled()) return Knobs.DEFAULTS;
            if (!inRollout(viewerId, cfg.getRolloutPercent())) return Knobs.DEFAULTS;
            return materialize(cfg);
        } catch (Exception e) {
            log.debug("[FEED-TUNING] resolve failed — defaults: {}", e.getMessage());
            return Knobs.DEFAULTS;
        }
    }

    /** The stored override row, if any (admin display). */
    public Optional<FeedRankingConfig> stored() {
        return repository.findById(FeedRankingConfig.SINGLETON_ID);
    }

    /** Build a Knobs from an arbitrary config row (also used by preview). */
    public Knobs materialize(FeedRankingConfig c) {
        Knobs d = Knobs.DEFAULTS;
        return new Knobs(
                nz(c.getWLike(), d.wLike()), nz(c.getWComment(), d.wComment()),
                nz(c.getWShare(), d.wShare()), nz(c.getWSave(), d.wSave()),
                nz(c.getWView(), d.wView()), nz(c.getWAffinity(), d.wAffinity()),
                nz(c.getHalfLifePost(), d.halfLifePost()), nz(c.getHalfLifeReel(), d.halfLifeReel()),
                nz(c.getHalfLifeChannel(), d.halfLifeChannel()),
                nz(c.getHalfLifeResearch(), d.halfLifeResearch()),
                nz(c.getHalfLifeQuestion(), d.halfLifeQuestion()),
                nz(c.getBoostMutual(), d.boostMutual()), nz(c.getBoostSelf(), d.boostSelf()),
                nz(c.getDampChannel(), d.dampChannel()), nz(c.getDampExplore(), d.dampExplore()),
                c.getMaxAuthorRun() == null ? d.maxAuthorRun() : c.getMaxAuthorRun());
    }

    /** Sticky per-user bucket: same user, same verdict, every request. */
    public static boolean inRollout(UUID viewerId, int rolloutPercent) {
        if (rolloutPercent >= 100) return true;
        if (rolloutPercent <= 0 || viewerId == null) return false;
        return Math.floorMod(viewerId.hashCode(), 100) < rolloutPercent;
    }

    /** Drop the 30s cache immediately after an admin config change. */
    public void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    private FeedRankingConfig current() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;
        FeedRankingConfig fresh = repository.findById(FeedRankingConfig.SINGLETON_ID).orElse(null);
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private static double nz(Double v, double dflt) {
        return v == null ? dflt : v;
    }
}
