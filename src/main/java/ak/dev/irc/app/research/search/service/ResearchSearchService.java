package ak.dev.irc.app.research.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.text.RichTextService;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchTag;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.search.document.ResearchSearchDocument;
import ak.dev.irc.app.research.search.repository.ResearchSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
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
