package ak.dev.irc.app.research.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.text.RichTextService;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchTag;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.search.document.ResearchSearchDocument;
import ak.dev.irc.app.research.search.repository.ResearchSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch-backed indexer for research papers.
 *
 * Index-only: query-time search is served by the unified
 * {@code /api/v1/search} endpoint (see {@code GlobalSearchService}). This
 * class owns the {@code irc-research} index and keeps it in sync with Postgres.
 *
 * Only PUBLISHED research is indexed. Draft / archived / retracted research
 * is removed from the index so the index acts as the public catalog.
 *
 * Indexing is async — never blocks the write path. Canonical store remains Postgres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchSearchService {

    private final ResearchSearchRepository searchRepo;
    private final RichTextService          richText;
    private final ResearchRepository       researchRepo;
    private final ElasticsearchOperations  esOps;

    /**
     * Index (or re-index) a research record. Drafts/archived/retracted are
     * removed instead — the index is the public catalog.
     */
    @Async
    public void indexAsync(Research research) {
        if (research == null) return;
        if (research.getStatus() != ResearchStatus.PUBLISHED
                || research.getDeletedAt() != null) {
            runWithRetry(
                    () -> searchRepo.deleteById(ResearchSearchDocument.idOf(research.getId())),
                    "[SEARCH] delete (lifecycle) research " + research.getId());
            return;
        }
        ResearchSearchDocument doc = buildDoc(research);
        runWithRetry(() -> searchRepo.save(doc),
                "[SEARCH] index research " + research.getId());
    }

    @Async
    public void deleteAsync(UUID researchId) {
        if (researchId == null) return;
        runWithRetry(() -> searchRepo.deleteById(researchId.toString()),
                "[SEARCH] delete research " + researchId);
    }

    /**
     * Result envelope for {@link #reindexAllPublished(boolean)}: drop status,
     * total records reindexed, page count, and wall-clock duration so the
     * caller knows the operation completed and how long it took.
     */
    public record ReindexResult(boolean indexDropped,
                                long    documentsIndexed,
                                int     pages,
                                long    durationMs,
                                String  note) {}

    /**
     * One-shot reindex of every PUBLISHED research record from Postgres
     * into the {@code irc-research} ES index. Used after schema changes
     * (new {@code @Field}s like {@code downloadCount}) so existing rows
     * pick up the new mapping rather than being left at 0 / missing.
     *
     * <p>If {@code dropFirst=true}, the existing index is deleted first
     * so the next {@code save()} recreates it from the up-to-date entity
     * mapping — the cleanest way to land new {@code @Field} additions.
     * Set {@code dropFirst=false} when the mapping is already current and
     * you only want to refresh the score fields (counters drifted, etc.).</p>
     *
     * <p>Runs synchronously on the caller thread — the admin endpoint
     * waits for completion so the response carries the final counts.
     * Index writes go through the same {@link EsRetry} the per-row
     * indexer uses, so a transient pooled-connection failure self-heals
     * without losing the whole job.</p>
     */
    @Transactional(readOnly = true)
    public ReindexResult reindexAllPublished(boolean dropFirst) {
        Instant start = Instant.now();

        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(ResearchSearchDocument.class).delete(),
                        "[SEARCH] drop irc-research");
                dropped = true;
                log.info("[SEARCH] irc-research index dropped — will be recreated from entity mapping on first save");
            } catch (Exception e) {
                // 404 (index didn't exist) is fine; anything else is logged
                // and we proceed — save() will still create the index lazily.
                log.warn("[SEARCH] irc-research drop best-effort failed (often harmless): {}",
                        e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(ResearchSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-research mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-research mapping recreate failed: {}", e.getMessage());
            }
        }

        final int PAGE_SIZE = 100;
        long indexed = 0;
        int pages = 0;
        int page = 0;
        while (true) {
            Page<Research> slice = researchRepo.findByStatusAndDeletedAtIsNullOrderByPublishedAtDesc(
                    ResearchStatus.PUBLISHED, PageRequest.of(page, PAGE_SIZE));
            if (slice.isEmpty()) break;

            List<ResearchSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (Research r : slice.getContent()) {
                docs.add(buildDoc(r));
            }
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex page " + page + " (" + docs.size() + " docs)");
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                // Per-page failure: log and continue — partial reindex is
                // better than zero, and the next page may write fine.
                log.warn("[SEARCH] reindex page {} failed ({} docs skipped): {}",
                        page, docs.size(), e.getMessage());
            }

            if (!slice.hasNext()) break;
            page++;
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d published research record(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped first)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexResult(dropped, indexed, pages, ms, note);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private ResearchSearchDocument buildDoc(Research research) {
        // Strip markup from the rendered HTML so ES relevance scores aren't
        // polluted by tag names and Markdown punctuation. Falls back to the
        // raw source for legacy rows that have no rendered HTML yet.
        String abstractIdx = richText.toPlainText(research.getAbstractHtml());
        if (abstractIdx == null) abstractIdx = research.getAbstractText();
        String descriptionIdx = richText.toPlainText(research.getDescriptionHtml());
        if (descriptionIdx == null) descriptionIdx = research.getDescription();

        return ResearchSearchDocument.builder()
                .id(ResearchSearchDocument.idOf(research.getId()))
                .title(research.getTitle())
                .abstractText(abstractIdx)
                .description(descriptionIdx)
                .keywords(research.getKeywords())
                .tags(research.getTags() == null ? List.of()
                        : research.getTags().stream()
                            .map(ResearchTag::getTagName).toList())
                .researcherId(research.getResearcher() == null ? null
                        : research.getResearcher().getId().toString())
                .researcherName(research.getResearcher() == null ? null
                        : research.getResearcher().getFullName())
                .researcherUsername(research.getResearcher() == null ? null
                        : research.getResearcher().getUsername())
                .status(research.getStatus().name())
                .ircId(research.getIrcId())
                .slug(research.getSlug())
                .viewCount(research.getViewCount())
                .citationCount(research.getCitationCount())
                .reactionCount(research.getReactionCount())
                .downloadCount(research.getDownloadCount())
                .publishedAt(research.getPublishedAt() == null ? null
                        : research.getPublishedAt().toInstant(ZoneOffset.UTC))
                .createdAt(research.getCreatedAt() == null ? null
                        : research.getCreatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }

    /**
     * Runs {@code action} via the shared {@link EsRetry} (retries once on a
     * stale-pooled-connection failure) and swallows any other exception with
     * a WARN — ES is an async secondary index, never block the write path.
     */
    private void runWithRetry(Runnable action, String label) {
        try {
            EsRetry.run(action, label);
        } catch (Exception e) {
            log.warn("{} failed: {}", label, e.getMessage());
        }
    }
}
