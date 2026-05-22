package ak.dev.irc.app.research.search.service;

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

    /**
     * Index (or re-index) a research record. Drafts/archived/retracted are
     * removed instead — the index is the public catalog.
     */
    @Async
    public void indexAsync(Research research) {
        if (research == null) return;
        try {
            if (research.getStatus() != ResearchStatus.PUBLISHED
                    || research.getDeletedAt() != null) {
                searchRepo.deleteById(ResearchSearchDocument.idOf(research.getId()));
                return;
            }
            ResearchSearchDocument doc = ResearchSearchDocument.builder()
                    .id(ResearchSearchDocument.idOf(research.getId()))
                    .title(research.getTitle())
                    .abstractText(research.getAbstractText())
                    .description(research.getDescription())
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
                    .doi(research.getDoi())
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
            searchRepo.save(doc);
        } catch (Exception e) {
            log.warn("[SEARCH] index research {} failed: {}", research.getId(), e.getMessage());
        }
    }

    @Async
    public void deleteAsync(UUID researchId) {
        if (researchId == null) return;
        try {
            searchRepo.deleteById(researchId.toString());
        } catch (Exception e) {
            log.warn("[SEARCH] delete research {} failed: {}", researchId, e.getMessage());
        }
    }
}
