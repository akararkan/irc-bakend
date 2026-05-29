package ak.dev.irc.app.common.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.GlobalSearchHit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Multi-index global search across posts (+reels), questions and research.
 *
 * <p>One ES query hits N indices in parallel — same shard-level latency as
 * searching a single index but lets each entity keep its own field schema
 * and per-type boost weights.</p>
 *
 * <p>Reels are filtered out of the posts index by {@code postType=REEL} when
 * the caller asks for {@code type=REEL}.</p>
 *
 * <p>The result envelope carries a {@code degraded} flag so the frontend can
 * distinguish a genuine empty result from an ES failure ({@link Result#degraded}).
 * On failure we still return an empty result rather than 5xx-ing, so the rest
 * of the page renders.</p>
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
    private final ObjectMapper            objectMapper = new ObjectMapper();

    /**
     * Search result envelope. {@code nextCursor} is non-null when cursor
     * mode was used and more pages may exist; {@code degraded=true} when
     * the ES call failed and {@code items} is therefore empty.
     */
    public record Result(List<GlobalSearchHit> items,
                         String nextCursor,
                         boolean degraded) {}

    /**
     * Offset-paged search (legacy mode). Prefer {@link #searchCursor} for
     * dropdowns / typeahead / live data; offset paging drifts as new content
     * lands during a scroll.
     *
     * @param query   user-supplied keywords
     * @param types   subset of entities to search; {@code null} or empty = all
     * @param page    0-indexed
     * @param size    per-page result count (across all indices)
     * @param expand  when {@code true}, inline preview fields from ES on each hit
     */
    public Result search(String query, Set<EntityType> types,
                         int page, int size, boolean expand) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), null, false);
        }
        List<String> indices = pickIndices(types);
        if (indices.isEmpty()) return new Result(List.of(), null, false);

        NativeQuery native_ = NativeQuery.builder()
                .withQuery(buildQuery(query, types))
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<Object> hits;
        try {
            // Single retry on stale-pooled-connection so a transient socket
            // recycle doesn't surface as a degraded result to the user.
            hits = EsRetry.call(
                    () -> esOps.search(native_, Object.class,
                            IndexCoordinates.of(indices.toArray(String[]::new))),
                    "[SEARCH] global");
        } catch (Exception e) {
            // ES is reachable but one of the target indexes doesn't exist
            // yet (fresh cluster with no documents of that type). That's a
            // legitimately empty result — don't flag degraded.
            if (EsRetry.isIndexNotFound(e)) {
                log.debug("[SEARCH] global: empty (missing index, ES is reachable): {}",
                        e.getMessage());
                return new Result(List.of(), null, false);
            }
            log.warn("[SEARCH] global search failed: {}", e.getMessage());
            return new Result(List.of(), null, true);
        }
        return new Result(toItems(hits, expand), null, false);
    }

    /**
     * Cursor-paged search using ES {@code search_after} on (score, _doc).
     * Stable across inserts: a new document landing mid-scroll won't shift
     * any subsequent page, and no row is ever duplicated or skipped.
     *
     * @param cursor opaque token returned by the previous page; null/blank for the head
     */
    public Result searchCursor(String query, Set<EntityType> types,
                               int size, String cursor, boolean expand) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), null, false);
        }
        List<String> indices = pickIndices(types);
        if (indices.isEmpty()) return new Result(List.of(), null, false);

        List<SortOptions> sort = List.of(
                SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))),
                SortOptions.of(s -> s.doc(o -> o.order(SortOrder.Asc))));

        List<FieldValue> after = Cursor.decode(cursor);

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(buildQuery(query, types))
                .withPageable(PageRequest.of(0, size))
                .withSort(sort);
        // Spring Data ES forwards searchAfter into the underlying query body.
        if (after != null) {
            builder.withSearchAfter(after.stream().map(this::toJavaSortValue).toList());
        }

        SearchHits<Object> hits;
        try {
            hits = EsRetry.call(
                    () -> esOps.search(builder.build(), Object.class,
                            IndexCoordinates.of(indices.toArray(String[]::new))),
                    "[SEARCH] global cursor");
        } catch (Exception e) {
            if (EsRetry.isIndexNotFound(e)) {
                log.debug("[SEARCH] global cursor: empty (missing index): {}", e.getMessage());
                return new Result(List.of(), null, false);
            }
            log.warn("[SEARCH] cursor search failed: {}", e.getMessage());
            return new Result(List.of(), null, true);
        }

        List<GlobalSearchHit> items = toItems(hits, expand);
        String nextCursor = Cursor.encode(lastSortValues(hits));
        return new Result(items, nextCursor, false);
    }

    // ── Query building ───────────────────────────────────────────────────────

    private static List<String> pickIndices(Set<EntityType> types) {
        Set<EntityType> active = (types == null || types.isEmpty())
                ? EnumSet.allOf(EntityType.class) : types;
        List<String> indices = new ArrayList<>(3);
        if (active.contains(EntityType.POST) || active.contains(EntityType.REEL)) indices.add(IDX_POSTS);
        if (active.contains(EntityType.QUESTION)) indices.add(IDX_QNA);
        if (active.contains(EntityType.RESEARCH)) indices.add(IDX_RESEARCH);
        return indices;
    }

    private static Query buildQuery(String query, Set<EntityType> types) {
        Set<EntityType> active = (types == null || types.isEmpty())
                ? EnumSet.allOf(EntityType.class) : types;
        return Query.of(q -> q.bool(b -> {
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
    }

    // ── Hit → DTO ────────────────────────────────────────────────────────────

    private List<GlobalSearchHit> toItems(SearchHits<Object> hits, boolean expand) {
        List<GlobalSearchHit> out = new ArrayList<>(hits.getSearchHits().size());
        for (SearchHit<Object> h : hits.getSearchHits()) {
            GlobalSearchHit hit = toHit(h, expand);
            if (hit != null) out.add(hit);
        }
        return out;
    }

    private GlobalSearchHit toHit(SearchHit<Object> h, boolean expand) {
        UUID id;
        try {
            id = UUID.fromString(h.getId());
        } catch (Exception e) {
            return null;
        }
        String type = switch (h.getIndex()) {
            case IDX_POSTS    -> deriveSocialType(h);
            case IDX_QNA      -> EntityType.QUESTION.name();
            case IDX_RESEARCH -> EntityType.RESEARCH.name();
            default           -> null;
        };
        if (type == null) return null;

        GlobalSearchHit.GlobalSearchHitBuilder b = GlobalSearchHit.builder()
                .contentType(type).contentId(id)
                .type(type).id(id)
                .score(h.getScore());
        if (expand) inlineBrief(b, h, type);
        return b.build();
    }

    /**
     * Pulls preview fields out of the ES source document so the frontend can
     * render search dropdowns without a follow-up hydration call per hit.
     * Only viewer-independent fields are inlined; saved/reacted/etc. stay out.
     */
    @SuppressWarnings("unchecked")
    private void inlineBrief(GlobalSearchHit.GlobalSearchHitBuilder b,
                             SearchHit<Object> h, String type) {
        Object src = h.getContent();
        if (!(src instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) src;

        // Each index uses its own preview field name — fall through to the next.
        String preview = firstNonBlank(
                str(m.get("title")),         // qna / research
                str(m.get("textContent")));  // post
        b.titlePreview(trimPreview(preview));

        b.authorUsername(firstNonBlank(
                str(m.get("authorUsername")),
                str(m.get("researcherUsername"))));
        b.authorName(firstNonBlank(
                str(m.get("authorName")),
                str(m.get("researcherName"))));
        Object createdAt = m.get("createdAt");
        if (createdAt instanceof String s && !s.isBlank()) {
            try { b.createdAt(Instant.parse(s)); }
            catch (Exception ignored) { /* leave null */ }
        }
    }

    private static String trimPreview(String s) {
        if (s == null) return null;
        return s.length() > 280 ? s.substring(0, 280) : s;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

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

    // ── Cursor encoding for search_after ─────────────────────────────────────

    private List<FieldValue> lastSortValues(SearchHits<Object> hits) {
        if (hits.isEmpty()) return null;
        List<Object> sortValues = hits.getSearchHits()
                .get(hits.getSearchHits().size() - 1).getSortValues();
        if (sortValues == null || sortValues.isEmpty()) return null;
        List<FieldValue> out = new ArrayList<>(sortValues.size());
        for (Object v : sortValues) out.add(toFieldValue(v));
        return out;
    }

    private FieldValue toFieldValue(Object v) {
        if (v == null) return FieldValue.NULL;
        if (v instanceof Number n)  return FieldValue.of(n.doubleValue());
        if (v instanceof Boolean b) return FieldValue.of(b);
        return FieldValue.of(v.toString());
    }

    private Object toJavaSortValue(FieldValue fv) {
        // search_after takes plain Java values, not FieldValue wrappers.
        return switch (fv._kind()) {
            case Double -> fv.doubleValue();
            case Long   -> fv.longValue();
            case Boolean -> fv.booleanValue();
            case String -> fv.stringValue();
            case Null   -> null;
            default     -> fv.toString();
        };
    }

    private final class Cursor {
        static String encode(List<FieldValue> sortValues) {
            if (sortValues == null || sortValues.isEmpty()) return null;
            try {
                List<Object> raw = new ArrayList<>(sortValues.size());
                for (FieldValue fv : sortValues) {
                    raw.add(switch (fv._kind()) {
                        case Double  -> fv.doubleValue();
                        case Long    -> fv.longValue();
                        case Boolean -> fv.booleanValue();
                        case String  -> fv.stringValue();
                        case Null    -> null;
                        default      -> fv.toString();
                    });
                }
                String json = new ObjectMapper().writeValueAsString(raw);
                return Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static List<FieldValue> decode(String token) {
            if (token == null || token.isBlank()) return null;
            try {
                String json = new String(Base64.getUrlDecoder().decode(token),
                        StandardCharsets.UTF_8);
                List<Object> raw = new ObjectMapper().readValue(json, List.class);
                List<FieldValue> out = new ArrayList<>(raw.size());
                for (Object v : raw) {
                    if (v == null) out.add(FieldValue.NULL);
                    else if (v instanceof Number n) out.add(FieldValue.of(n.doubleValue()));
                    else if (v instanceof Boolean b) out.add(FieldValue.of(b));
                    else out.add(FieldValue.of(v.toString()));
                }
                return out;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
