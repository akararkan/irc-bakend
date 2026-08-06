package ak.dev.irc.app.common.tag.job;

import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.entity.TagCounterEntity;
import ak.dev.irc.app.common.tag.entity.TrendingTagEntity;
import ak.dev.irc.app.common.tag.repository.TagCounterRepository;
import ak.dev.irc.app.common.tag.repository.TrendingTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Rebuilds the {@code trending_tags} snapshot from {@code tag_counters}.
 *
 * <p>Cassandra can't sort by a counter, so for each scope ({@code ALL},
 * {@code QUESTION}, {@code RESEARCH}) this reads the scope's single counter
 * partition, sorts by usage in memory, and rewrites the top-{@value #TOP_K} as
 * pre-ranked rows. The trending endpoint then serves a single ordered partition
 * read — no per-request sorting.</p>
 *
 * <p>Runs every {@code app.tags.trending-refresh-ms} (default 10 min) after a
 * short startup delay. Trending is intentionally near-real-time, not
 * to-the-second; the counters stay exact, only the leaderboard is periodic.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingTagJob {

    private static final int TOP_K = 100;
    // Every scope the tagging pipeline counts into MUST be rebuilt here, or
    // GET /tags/trending?scope=X serves a permanently empty partition for it.
    private static final String[] SCOPES = {
            ContentType.SCOPE_ALL, ContentType.QUESTION.name(), ContentType.RESEARCH.name(),
            ContentType.POST.name(), ContentType.REEL.name()
    };

    private final TagCounterRepository  tagCounterRepo;
    private final TrendingTagRepository trendingRepo;
    private final CqlOperations         cqlOperations;
    private final ak.dev.irc.app.common.tag.repository.TrendingTagOverrideRepository overrideRepo;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    @Scheduled(initialDelayString = "${app.tags.trending-initial-delay-ms:60000}",
               fixedDelayString   = "${app.tags.trending-refresh-ms:600000}")
    public void rebuildTrending() {
        if (jobPause.isPaused("trending-rebuild")) return;
        // One override load per run, not per scope.
        List<ak.dev.irc.app.common.tag.entity.TrendingTagOverride> overrides;
        try {
            overrides = overrideRepo.findByRevokedAtIsNull().stream()
                    .filter(ak.dev.irc.app.common.tag.entity.TrendingTagOverride::isActive)
                    .toList();
        } catch (Exception e) {
            log.warn("[TRENDING] override load failed (rebuilding without): {}", e.getMessage());
            overrides = List.of();
        }
        for (String scope : SCOPES) {
            try {
                rebuildScope(scope, overrides);
            } catch (Exception e) {
                log.warn("[TRENDING] rebuild scope {} failed: {}", scope, e.getMessage());
            }
        }
    }

    private void rebuildScope(String scope,
                              List<ak.dev.irc.app.common.tag.entity.TrendingTagOverride> overrides) {
        java.util.Set<String> banned = new java.util.HashSet<>();
        List<ak.dev.irc.app.common.tag.entity.TrendingTagOverride> pins = new java.util.ArrayList<>();
        for (var o : overrides) {
            if (!o.appliesTo(scope)) continue;
            if (o.getType() == ak.dev.irc.app.common.tag.entity.TrendingTagOverride.OverrideType.BAN) {
                banned.add(o.getTagNormalized());
            } else {
                pins.add(o);
            }
        }

        List<TagCounterEntity> counters = tagCounterRepo.findByScope(scope);
        List<TagCounterEntity> top = counters.stream()
                .filter(c -> c.getUsageCount() != null && c.getUsageCount() > 0)
                .filter(c -> !banned.contains(c.getTag()))     // editorial BAN, pre-cut
                .sorted(Comparator.comparingLong(TagCounterEntity::getUsageCount).reversed())
                .limit(TOP_K)
                .toList();

        // Splice PINs at their requested rank after the organic cut.
        java.util.List<String> ordered = new java.util.ArrayList<>(top.size() + pins.size());
        java.util.Map<String, Long> usage = new java.util.HashMap<>();
        for (TagCounterEntity c : top) {
            ordered.add(c.getTag());
            usage.put(c.getTag(), c.getUsageCount());
        }
        pins.sort(Comparator.comparingInt(o ->
                o.getPinnedRank() == null ? 0 : o.getPinnedRank()));
        for (var pin : pins) {
            ordered.remove(pin.getTagNormalized());
            int rank = pin.getPinnedRank() == null ? 0
                    : Math.max(0, Math.min(pin.getPinnedRank(), ordered.size()));
            ordered.add(rank, pin.getTagNormalized());
            usage.putIfAbsent(pin.getTagNormalized(), 0L);
        }
        if (ordered.size() > TOP_K) {
            ordered = ordered.subList(0, TOP_K);
        }

        // Replace the whole scope partition atomically enough for a leaderboard.
        cqlOperations.execute("DELETE FROM trending_tags WHERE scope = ?", scope);

        int rank = 0;
        for (String tag : ordered) {
            trendingRepo.save(TrendingTagEntity.builder()
                    .scope(scope).tagRank(rank++)
                    .tag(tag).usageCount(usage.getOrDefault(tag, 0L))
                    .build());
        }
        log.debug("[TRENDING] scope {} → {} tags ({} banned, {} pinned)",
                scope, ordered.size(), banned.size(), pins.size());
    }
}
