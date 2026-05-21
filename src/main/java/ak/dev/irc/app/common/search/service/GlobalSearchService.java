package ak.dev.irc.app.common.search.service;

import ak.dev.irc.app.common.search.dto.GlobalSearchHit;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Multi-index global search across posts (+reels), questions and research.
 *
 * One ES query hits N indices in parallel — same shard-level latency as
 * searching a single index but lets each entity keep its own field schema
 * and per-type boost weights.
 *
 * Reels are filtered out of the posts index by {@code postType=REEL} when
 * the caller asks for {@code type=REEL}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSearchService {

    public enum EntityType { POST, REEL, QUESTION, RESEARCH }

    private static final String IDX_POSTS    = "irc-posts";
    private static final String IDX_QNA      = "irc-qna";
    private static final String IDX_RESEARCH = "irc-research";

    private final ElasticsearchOperations esOps;

    /**
     * @param query   user-supplied keywords
     * @param types   subset of entities to search; {@code null} or empty = all
     * @param page    0-indexed
     * @param size    per-page result count (across all indices)
     */
    public List<GlobalSearchHit> search(String query,
                                        Set<EntityType> types,
                                        int page,
                                        int size) {
        if (query == null || query.isBlank()) return List.of();

        Set<EntityType> active = (types == null || types.isEmpty())
                ? EnumSet.allOf(EntityType.class)
                : types;

        List<String> indices = new ArrayList<>(3);
        if (active.contains(EntityType.POST) || active.contains(EntityType.REEL)) indices.add(IDX_POSTS);
        if (active.contains(EntityType.QUESTION)) indices.add(IDX_QNA);
        if (active.contains(EntityType.RESEARCH)) indices.add(IDX_RESEARCH);
        if (indices.isEmpty()) return List.of();

        // Cross-index multi_match. Each index supplies its own fields — ES
        // silently ignores fields that don't exist on a given index.
        Query esQuery = Query.of(q -> q.bool(b -> {
            b.must(m -> m.multiMatch(mm -> mm
                    .query(query)
                    .fields(
                            "title^4", "abstractText^2", "keywords^2",
                            "body^2", "tags^2",
                            "textContent^3", "hashtags^2", "description",
                            "authorName", "authorUsername",
                            "researcherName", "researcherUsername"
                    )));
            // Reels-only requests narrow the posts index by postType.
            if (active.contains(EntityType.REEL) && !active.contains(EntityType.POST)) {
                b.filter(f -> f.term(t -> t.field("postType").value("REEL")));
            }
            return b;
        }));

        NativeQuery native_ = NativeQuery.builder()
                .withQuery(esQuery)
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<Object> hits;
        try {
            hits = esOps.search(native_, Object.class,
                    IndexCoordinates.of(indices.toArray(String[]::new)));
        } catch (Exception e) {
            log.warn("[SEARCH] global search failed: {}", e.getMessage());
            return List.of();
        }

        return hits.getSearchHits().stream()
                .map(this::toHit)
                .filter(Objects::nonNull)
                .toList();
    }

    private GlobalSearchHit toHit(SearchHit<Object> h) {
        try {
            UUID id = UUID.fromString(h.getId());
            String type = switch (h.getIndex()) {
                case IDX_POSTS    -> deriveSocialType(h);
                case IDX_QNA      -> EntityType.QUESTION.name();
                case IDX_RESEARCH -> EntityType.RESEARCH.name();
                default           -> null;
            };
            if (type == null) return null;
            return new GlobalSearchHit(type, id, h.getScore());
        } catch (Exception e) {
            return null;
        }
    }

    /** Posts and reels share an index — distinguish by the postType source field. */
    @SuppressWarnings("unchecked")
    private String deriveSocialType(SearchHit<Object> h) {
        Object src = h.getContent();
        if (src instanceof Map<?, ?> m) {
            Object pt = ((Map<String, Object>) m).get("postType");
            if (pt != null && "REEL".equalsIgnoreCase(pt.toString())) return EntityType.REEL.name();
        }
        return EntityType.POST.name();
    }
}
