package ak.dev.irc.app.common.search.controller;

import ak.dev.irc.app.common.search.service.GlobalSearchService;
import ak.dev.irc.app.common.search.service.GlobalSearchService.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Single global search endpoint — one ES query hits posts, Q&A and research
 * indices in parallel and returns score-ordered hits stamped with content type.
 *
 * <p>This is the <em>only</em> full-text search entry point in the API: the
 * per-entity {@code /search} endpoints that used to live on
 * {@code /api/v1/posts}, {@code /api/v1/researches} and
 * {@code /api/v1/questions} were retired in favour of this unified path.
 * To scope the search to one entity type, pass any subset of
 * {@code types=POST,REEL,QUESTION,ANSWER,RESEARCH,USER,CHANNEL,SOUND}
 * (CSV combinations are supported, e.g. {@code types=POST,REEL}).</p>
 *
 * <h3>Response envelope</h3>
 * <pre>
 * {
 *   "query":      "ramadan",
 *   "types":      ["POST","REEL","QUESTION","RESEARCH"],
 *   "page":       0,
 *   "size":       20,
 *   "results":    [ GlobalSearchHit, ... ],
 *   "nextCursor": "eyJzY29yZSI6ICJyZWxldmFu..."  -- cursor mode only
 *   "degraded":   false                          -- true when ES is down
 * }
 * </pre>
 *
 * <p>The {@code X-Search-Degraded: true} response header mirrors
 * {@code degraded} for callers that bypass JSON parsing (e.g. fetch + AbortController).</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService globalSearch;

    /**
     * @param q      query string (required)
     * @param types  optional CSV — any subset of
     *               {@code POST,REEL,QUESTION,ANSWER,RESEARCH,USER,CHANNEL,SOUND}.
     *               Omitted = all eight.
     * @param cursor opaque cursor token from the previous response's
     *               {@code nextCursor}. When supplied, the {@code page} param
     *               is ignored and the result is paged via ES {@code search_after}
     *               (stable across inserts — preferred for typeahead / live feeds).
     * @param page   0-indexed (legacy offset mode; ignored when {@code cursor} is set)
     * @param size   per-page result count (across all indices)
     * @param expand when {@code true}, each hit is enriched with
     *               {@code titlePreview}/{@code authorUsername}/{@code authorName}/{@code createdAt}
     *               pulled from the ES source — eliminates the typeahead N+1.
     *               Default: {@code true}. Pass {@code expand=false} to fall
     *               back to the bare {@code (contentType, contentId, score)} shape.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "true") boolean expand) {

        Set<EntityType> filter = parseTypes(types);
        int clampedSize = Math.min(Math.max(size, 1), 100);

        GlobalSearchService.Result result = (cursor != null && !cursor.isBlank())
                ? globalSearch.searchCursor(q, filter, clampedSize, cursor, expand)
                : globalSearch.search(q, filter, page, clampedSize, expand);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query",    q);
        body.put("types",    filter.isEmpty() ? EnumSet.allOf(EntityType.class) : filter);
        body.put("page",     cursor == null || cursor.isBlank() ? page : 0);
        body.put("size",     clampedSize);
        body.put("results",  result.items());
        body.put("degraded", result.degraded());
        // nextCursor is only meaningful in cursor mode.
        if (cursor != null && !cursor.isBlank()) {
            body.put("nextCursor", result.nextCursor() == null ? "" : result.nextCursor());
        }

        HttpHeaders headers = new HttpHeaders();
        if (result.degraded()) headers.set("X-Search-Degraded", "true");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static Set<EntityType> parseTypes(String csv) {
        if (csv == null || csv.isBlank() || "all".equalsIgnoreCase(csv)) return Set.of();
        EnumSet<EntityType> out = EnumSet.noneOf(EntityType.class);
        for (String s : csv.split(",")) {
            try { out.add(EntityType.valueOf(s.trim().toUpperCase())); }
            catch (IllegalArgumentException ignored) { /* skip unknown */ }
        }
        return out;
    }
}
