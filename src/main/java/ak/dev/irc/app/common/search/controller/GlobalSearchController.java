package ak.dev.irc.app.common.search.controller;

import ak.dev.irc.app.common.search.dto.GlobalSearchHit;
import ak.dev.irc.app.common.search.service.GlobalSearchService;
import ak.dev.irc.app.common.search.service.GlobalSearchService.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Single global search endpoint — one ES query hits posts, Q&A and research
 * indices in parallel and returns score-ordered hits stamped with entity type.
 *
 * This is the <em>only</em> full-text search entry point in the API: the
 * per-entity {@code /search} endpoints that used to live on
 * {@code /api/v1/posts}, {@code /api/v1/researches} and
 * {@code /api/v1/questions} were retired in favour of this unified path.
 * To scope the search to one entity type, pass {@code types=POST},
 * {@code types=REEL}, {@code types=QUESTION} or {@code types=RESEARCH}
 * (CSV combinations are supported, e.g. {@code types=POST,REEL}).
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService globalSearch;

    /**
     * @param q     query string (required)
     * @param types optional CSV — any subset of {@code POST,REEL,QUESTION,RESEARCH}.
     *              Omitted = all four.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Set<EntityType> filter = parseTypes(types);
        List<GlobalSearchHit> hits = globalSearch.search(q, filter, page, size);

        return ResponseEntity.ok(Map.of(
                "query",   q,
                "types",   filter.isEmpty() ? EnumSet.allOf(EntityType.class) : filter,
                "page",    page,
                "size",    size,
                "results", hits));
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
