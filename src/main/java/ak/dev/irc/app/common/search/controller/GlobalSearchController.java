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
 * Per-entity endpoints (/api/v1/posts/search, /api/v1/researches/search,
 * /api/v1/questions/search) still exist for type-specific search UIs.
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
