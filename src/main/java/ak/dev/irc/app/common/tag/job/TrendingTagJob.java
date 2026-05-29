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
    private static final String[] SCOPES = {
            ContentType.SCOPE_ALL, ContentType.QUESTION.name(), ContentType.RESEARCH.name()
    };

    private final TagCounterRepository  tagCounterRepo;
    private final TrendingTagRepository trendingRepo;
    private final CqlOperations         cqlOperations;

    @Scheduled(initialDelayString = "${app.tags.trending-initial-delay-ms:60000}",
               fixedDelayString   = "${app.tags.trending-refresh-ms:600000}")
    public void rebuildTrending() {
        for (String scope : SCOPES) {
            try {
                rebuildScope(scope);
            } catch (Exception e) {
                log.warn("[TRENDING] rebuild scope {} failed: {}", scope, e.getMessage());
            }
        }
    }

    private void rebuildScope(String scope) {
        List<TagCounterEntity> counters = tagCounterRepo.findByScope(scope);
        List<TagCounterEntity> top = counters.stream()
                .filter(c -> c.getUsageCount() != null && c.getUsageCount() > 0)
                .sorted(Comparator.comparingLong(TagCounterEntity::getUsageCount).reversed())
                .limit(TOP_K)
                .toList();

        // Replace the whole scope partition atomically enough for a leaderboard.
        cqlOperations.execute("DELETE FROM trending_tags WHERE scope = ?", scope);

        int rank = 0;
        for (TagCounterEntity c : top) {
            trendingRepo.save(TrendingTagEntity.builder()
                    .scope(scope).tagRank(rank++)
                    .tag(c.getTag()).usageCount(c.getUsageCount())
                    .build());
        }
        log.debug("[TRENDING] scope {} → {} tags", scope, top.size());
    }
}
