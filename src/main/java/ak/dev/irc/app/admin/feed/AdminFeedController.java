package ak.dev.irc.app.admin.feed;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.post.cassandra.repository.UserAuthorAffinityRepository;
import ak.dev.irc.app.post.cassandra.service.FeedRankingService;
import ak.dev.irc.app.post.cassandra.service.HomeFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ranked-feed observability (blueprint §3.9): the read-only weight registry
 * (all compile-time constants — the dashboard says "recompile-only" instead
 * of pretending to tune), the per-user explain debug view, and the affinity
 * inspector.
 */
@RestController
@RequestMapping("/api/v1/admin/feed")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ANALYST')")   // §6 matrix: feed observability is ANALYST-readable
public class AdminFeedController {

    private final HomeFeedService homeFeedService;
    private final UserAuthorAffinityRepository affinityRepository;
    private final FeedTuningService feedTuningService;
    private final FeedRankingConfigRepository configRepository;
    private final AdminAuditor adminAuditor;

    @GetMapping("/weights")
    public ResponseEntity<Map<String, Object>> weights() {
        return ResponseEntity.ok(FeedRankingService.knobRegistry());
    }

    /**
     * Scored-candidate breakdown of one user's next ranked page — what the
     * pipeline would actually serve them right now, with per-item source and
     * final score.
     */
    @GetMapping("/explain/{userId}")
    public ResponseEntity<Map<String, Object>> explain(@PathVariable UUID userId,
                                                       @RequestParam(defaultValue = "20") int limit) {
        var ranked = homeFeedService.rankedHomeFeed(userId, Pages.clamp(limit), null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var item : ranked.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("authorId", item.authorId());
            row.put("entityType", item.entityType());
            row.put("postType", item.postType());
            row.put("source", item.source());
            row.put("rankScore", item.rankScore());
            row.put("createdAt", item.createdAt());
            items.add(row);
        }
        adminAuditor.record(AuditOperation.READ, "User", userId, "ADMIN_FEED_EXPLAIN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("items", items);
        body.put("liveRailSize", ranked.liveNow() == null ? 0 : ranked.liveNow().size());
        body.put("nextCursor", ranked.nextCursor());
        return ResponseEntity.ok(body);
    }

    // ── runtime config (search-feed-trending.md §2.3 — now tunable) ─────

    /** The stored override row, the effective knob set, and the defaults. */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        var stored = feedTuningService.stored();
        body.put("stored", stored.orElse(null));
        body.put("effectiveWhenInRollout", stored.map(feedTuningService::materialize)
                .orElse(FeedRankingService.Knobs.DEFAULTS));
        body.put("defaults", FeedRankingService.Knobs.DEFAULTS);
        body.put("rollout", stored.map(c -> Map.of(
                        "enabled", c.isEnabled(),
                        "rolloutPercent", c.getRolloutPercent(),
                        "bucketing", "Math.floorMod(userId.hashCode(),100) < rolloutPercent — "
                                + "sticky per user"))
                .orElse(Map.of("enabled", false, "rolloutPercent", 0,
                        "bucketing", "no override row — everyone on defaults")));
        return ResponseEntity.ok(body);
    }

    public record FeedConfigPatch(
            Boolean enabled, Integer rolloutPercent,
            Double wLike, Double wComment, Double wShare, Double wSave, Double wView,
            Double wAffinity,
            Double halfLifePost, Double halfLifeReel, Double halfLifeChannel,
            Double halfLifeResearch, Double halfLifeQuestion,
            Double boostMutual, Double boostSelf, Double dampChannel, Double dampExplore,
            Integer maxAuthorRun) {}

    /** Partial update — only named knobs are pinned; live within ≤30s. */
    @org.springframework.web.bind.annotation.PatchMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    @ak.dev.irc.app.admin.support.RequiresStepUp
    public ResponseEntity<FeedRankingConfig> patchConfig(
            @org.springframework.web.bind.annotation.RequestBody FeedConfigPatch patch) {
        validate(patch);
        FeedRankingConfig cfg = configRepository.findById(FeedRankingConfig.SINGLETON_ID)
                .orElseGet(() -> FeedRankingConfig.builder().build());
        String before = describe(cfg);

        if (patch.enabled() != null) cfg.setEnabled(patch.enabled());
        if (patch.rolloutPercent() != null) cfg.setRolloutPercent(patch.rolloutPercent());
        if (patch.wLike() != null) cfg.setWLike(patch.wLike());
        if (patch.wComment() != null) cfg.setWComment(patch.wComment());
        if (patch.wShare() != null) cfg.setWShare(patch.wShare());
        if (patch.wSave() != null) cfg.setWSave(patch.wSave());
        if (patch.wView() != null) cfg.setWView(patch.wView());
        if (patch.wAffinity() != null) cfg.setWAffinity(patch.wAffinity());
        if (patch.halfLifePost() != null) cfg.setHalfLifePost(patch.halfLifePost());
        if (patch.halfLifeReel() != null) cfg.setHalfLifeReel(patch.halfLifeReel());
        if (patch.halfLifeChannel() != null) cfg.setHalfLifeChannel(patch.halfLifeChannel());
        if (patch.halfLifeResearch() != null) cfg.setHalfLifeResearch(patch.halfLifeResearch());
        if (patch.halfLifeQuestion() != null) cfg.setHalfLifeQuestion(patch.halfLifeQuestion());
        if (patch.boostMutual() != null) cfg.setBoostMutual(patch.boostMutual());
        if (patch.boostSelf() != null) cfg.setBoostSelf(patch.boostSelf());
        if (patch.dampChannel() != null) cfg.setDampChannel(patch.dampChannel());
        if (patch.dampExplore() != null) cfg.setDampExplore(patch.dampExplore());
        if (patch.maxAuthorRun() != null) cfg.setMaxAuthorRun(patch.maxAuthorRun());

        cfg.setUpdatedAt(java.time.LocalDateTime.now());
        cfg.setUpdatedBy(ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null));
        cfg = configRepository.save(cfg);
        feedTuningService.invalidate();

        adminAuditor.record(AuditOperation.UPDATE, "FeedRankingConfig", null,
                "FEED_CONFIG_CHANGED", before + " -> " + describe(cfg));
        return ResponseEntity.ok(cfg);
    }

    public record FeedPreviewRequest(UUID userId, Integer limit, FeedConfigPatch overrides) {}

    /**
     * Shadow-score a real user's next page under proposed knobs WITHOUT
     * persisting anything: baseline ranking and override ranking side by
     * side, with per-item rank deltas.
     */
    @org.springframework.web.bind.annotation.PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> preview(
            @org.springframework.web.bind.annotation.RequestBody FeedPreviewRequest req) {
        if (req.userId() == null) {
            throw new ak.dev.irc.app.common.exception.BadRequestException(
                    AdminOpsMessages.MISSING_USER_MSG, AdminOpsMessages.MISSING_USER);
        }
        int limit = Pages.clamp(req.limit() == null ? 20 : req.limit());

        FeedRankingService.Knobs baseline = feedTuningService.effective(req.userId());
        FeedRankingService.Knobs proposed = baseline;
        if (req.overrides() != null) {
            validate(req.overrides());
            FeedRankingConfig scratch = FeedRankingConfig.builder().build();
            applyOverrides(scratch, req.overrides());
            proposed = feedTuningService.materialize(scratch);
        }

        var baseFeed = homeFeedService.rankedHomeFeed(req.userId(), limit, null, baseline);
        var overFeed = homeFeedService.rankedHomeFeed(req.userId(), limit, null, proposed);

        Map<UUID, Integer> baseRank = new LinkedHashMap<>();
        for (int i = 0; i < baseFeed.items().size(); i++) {
            baseRank.put(baseFeed.items().get(i).id(), i);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < overFeed.items().size(); i++) {
            var item = overFeed.items().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i);
            row.put("id", item.id());
            row.put("authorId", item.authorId());
            row.put("entityType", item.entityType());
            row.put("source", item.source());
            row.put("score", item.rankScore());
            Integer was = baseRank.get(item.id());
            row.put("baselineRank", was);
            row.put("rankDelta", was == null ? null : was - i);
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", req.userId());
        body.put("baselineKnobs", baseline);
        body.put("proposedKnobs", proposed);
        body.put("preview", rows);
        body.put("note", AdminOpsMessages.NOTE_FEED_PREVIEW_SHADOW);
        return ResponseEntity.ok(body);
    }

    private static void applyOverrides(FeedRankingConfig cfg, FeedConfigPatch p) {
        if (p.wLike() != null) cfg.setWLike(p.wLike());
        if (p.wComment() != null) cfg.setWComment(p.wComment());
        if (p.wShare() != null) cfg.setWShare(p.wShare());
        if (p.wSave() != null) cfg.setWSave(p.wSave());
        if (p.wView() != null) cfg.setWView(p.wView());
        if (p.wAffinity() != null) cfg.setWAffinity(p.wAffinity());
        if (p.halfLifePost() != null) cfg.setHalfLifePost(p.halfLifePost());
        if (p.halfLifeReel() != null) cfg.setHalfLifeReel(p.halfLifeReel());
        if (p.halfLifeChannel() != null) cfg.setHalfLifeChannel(p.halfLifeChannel());
        if (p.halfLifeResearch() != null) cfg.setHalfLifeResearch(p.halfLifeResearch());
        if (p.halfLifeQuestion() != null) cfg.setHalfLifeQuestion(p.halfLifeQuestion());
        if (p.boostMutual() != null) cfg.setBoostMutual(p.boostMutual());
        if (p.boostSelf() != null) cfg.setBoostSelf(p.boostSelf());
        if (p.dampChannel() != null) cfg.setDampChannel(p.dampChannel());
        if (p.dampExplore() != null) cfg.setDampExplore(p.dampExplore());
        if (p.maxAuthorRun() != null) cfg.setMaxAuthorRun(p.maxAuthorRun());
    }

    private static void validate(FeedConfigPatch p) {
        if (p.rolloutPercent() != null && (p.rolloutPercent() < 0 || p.rolloutPercent() > 100)) {
            throw new ak.dev.irc.app.common.exception.BadRequestException(
                    AdminOpsMessages.INVALID_ROLLOUT_MSG, AdminOpsMessages.INVALID_ROLLOUT);
        }
        if (p.maxAuthorRun() != null && (p.maxAuthorRun() < 1 || p.maxAuthorRun() > 10)) {
            throw new ak.dev.irc.app.common.exception.BadRequestException(
                    AdminOpsMessages.INVALID_KNOB_RUN_MSG, AdminOpsMessages.INVALID_KNOB);
        }
        for (Double v : new Double[]{p.wLike(), p.wComment(), p.wShare(), p.wSave(), p.wView(),
                p.wAffinity(), p.halfLifePost(), p.halfLifeReel(), p.halfLifeChannel(),
                p.halfLifeResearch(), p.halfLifeQuestion(), p.boostMutual(), p.boostSelf(),
                p.dampChannel(), p.dampExplore()}) {
            if (v != null && (v.isNaN() || v.isInfinite() || v < 0 || v > 1000)) {
                throw new ak.dev.irc.app.common.exception.BadRequestException(
                        AdminOpsMessages.INVALID_KNOB_MSG, AdminOpsMessages.INVALID_KNOB);
            }
        }
    }

    private static String describe(FeedRankingConfig c) {
        return "enabled=" + c.isEnabled() + " rollout=" + c.getRolloutPercent() + "%"
                + " overrides={"
                + (c.getWLike() == null ? "" : " wLike=" + c.getWLike())
                + (c.getWComment() == null ? "" : " wComment=" + c.getWComment())
                + (c.getWShare() == null ? "" : " wShare=" + c.getWShare())
                + (c.getWSave() == null ? "" : " wSave=" + c.getWSave())
                + (c.getWView() == null ? "" : " wView=" + c.getWView())
                + (c.getWAffinity() == null ? "" : " wAffinity=" + c.getWAffinity())
                + (c.getHalfLifePost() == null ? "" : " hlPost=" + c.getHalfLifePost())
                + (c.getHalfLifeReel() == null ? "" : " hlReel=" + c.getHalfLifeReel())
                + (c.getHalfLifeChannel() == null ? "" : " hlChannel=" + c.getHalfLifeChannel())
                + (c.getHalfLifeResearch() == null ? "" : " hlResearch=" + c.getHalfLifeResearch())
                + (c.getHalfLifeQuestion() == null ? "" : " hlQuestion=" + c.getHalfLifeQuestion())
                + (c.getBoostMutual() == null ? "" : " boostMutual=" + c.getBoostMutual())
                + (c.getBoostSelf() == null ? "" : " boostSelf=" + c.getBoostSelf())
                + (c.getDampChannel() == null ? "" : " dampChannel=" + c.getDampChannel())
                + (c.getDampExplore() == null ? "" : " dampExplore=" + c.getDampExplore())
                + (c.getMaxAuthorRun() == null ? "" : " maxRun=" + c.getMaxAuthorRun())
                + " }";
    }

    /** Viewer→author engagement counters feeding the A term. Behavioral data — audited. */
    @GetMapping("/affinity/{userId}")
    public ResponseEntity<List<Map<String, Object>>> affinity(@PathVariable UUID userId,
                                                              @RequestParam(defaultValue = "50") int limit) {
        adminAuditor.record(AuditOperation.READ, "User", userId, "ADMIN_FEED_AFFINITY_VIEW");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : affinityRepository.findAllForUser(userId, Pages.clamp(limit))) {
            rows.add(Map.of(
                    "authorId", row.getAuthorId(),
                    "interactions", row.getInteractions() == null ? 0L : row.getInteractions()));
        }
        return ResponseEntity.ok(rows);
    }
}
