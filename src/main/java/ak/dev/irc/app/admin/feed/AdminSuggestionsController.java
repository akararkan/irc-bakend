package ak.dev.irc.app.admin.feed;

import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.user.repository.SuggestionDismissalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PYMK observability (blueprint §3.9/§3.13): the knob registry and the
 * per-user explain view — the stored suggestion set with score/reason plus
 * persistent dismissals.
 */
@RestController
@RequestMapping("/api/v1/admin/suggestions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ANALYST')")   // §6 matrix: PYMK observability is ANALYST-readable
public class AdminSuggestionsController {

    private final FriendSuggestionService friendSuggestionService;
    private final SuggestionDismissalRepository dismissalRepository;

    @GetMapping("/knobs")
    public ResponseEntity<Map<String, Object>> knobs() {
        return ResponseEntity.ok(FriendSuggestionService.knobRegistry());
    }

    /** Per-source contribution view: the stored top-50 with reasons + dismissals. */
    @GetMapping("/explain/{userId}")
    public ResponseEntity<Map<String, Object>> explain(@PathVariable UUID userId) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (var s : friendSuggestionService.topSuggestionsFor(userId, 50)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidateId", s.getCandidateId());
            row.put("storedScore", s.getScore());
            row.put("reason", s.getReason());
            row.put("computedAt", s.getComputedAt());
            suggestions.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("suggestions", suggestions);
        body.put("dismissedCandidateIds", dismissalRepository.findDismissedCandidateIds(userId));
        return ResponseEntity.ok(body);
    }
}
