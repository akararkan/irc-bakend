package ak.dev.irc.app.common.search;

import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single search entry point — fast, ranked, multi-corpus.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code GET /api/v1/search?q=quantum} — every corpus, 20 hits each.</li>
 *   <li>{@code GET /api/v1/search?q=quantum&type=POST&type=REEL&limit=10} —
 *       only posts and reels, 10 each.</li>
 *   <li>{@code GET /api/v1/search/users?q=akar} — typed, paged endpoint.</li>
 * </ul>
 *
 * <p>Query syntax matches Postgres {@code websearch_to_tsquery}:
 * <ul>
 *   <li>{@code "exact phrase"} — quoted phrase match.</li>
 *   <li>{@code term1 OR term2} — boolean OR.</li>
 *   <li>{@code -unwanted} — exclude term.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private static final int DEFAULT_LIMIT = 20;

    private final UnifiedSearchService service;

    @GetMapping
    public ResponseEntity<UnifiedSearchResult> search(
            @RequestParam("q") String q,
            @RequestParam(value = "type", required = false) List<SearchType> type,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        Set<SearchType> requested = (type == null || type.isEmpty()) ? null : new HashSet<>(type);
        UUID viewerId = user != null ? user.getId() : null;
        return ResponseEntity.ok(service.search(q, requested, limit, viewerId));
    }

    @GetMapping("/posts")
    public ResponseEntity<List<SearchHit>> posts(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.searchPosts(q, user != null ? user.getId() : null, limit));
    }

    @GetMapping("/reels")
    public ResponseEntity<List<SearchHit>> reels(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.searchReels(q, user != null ? user.getId() : null, limit));
    }

    @GetMapping("/research")
    public ResponseEntity<List<SearchHit>> research(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.searchResearch(q, limit));
    }

    @GetMapping("/questions")
    public ResponseEntity<List<SearchHit>> questions(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.searchQuestions(q, limit));
    }

    @GetMapping("/answers")
    public ResponseEntity<List<SearchHit>> answers(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.searchAnswers(q, limit));
    }

    @GetMapping("/users")
    public ResponseEntity<List<SearchHit>> users(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.searchUsers(q, limit));
    }

    /**
     * Hashtag lookup — accepts the tag with or without a leading {@code #}.
     * Backed by the trigram GIN index so even rare tags resolve in single-digit
     * milliseconds.
     */
    @GetMapping("/tags/{tag}")
    public ResponseEntity<List<SearchHit>> tag(
            @PathVariable String tag,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                service.searchByHashtag(tag, user != null ? user.getId() : null, limit));
    }
}
