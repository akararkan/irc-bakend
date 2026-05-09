package ak.dev.irc.app.common.controller;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.service.MentionSuggestionService;
import ak.dev.irc.app.common.service.MentionSuggestionService.Suggestion;
import ak.dev.irc.app.common.util.MentionExtractor;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public mention helpers used by the front-end's compose box:
 *
 * <ul>
 *   <li>{@code GET /api/v1/mentions/suggest?q=ak&limit=10} — autocomplete
 *       candidates for a partial handle.</li>
 *   <li>{@code POST /api/v1/mentions/parse} — extract every {@code @username}
 *       (and the special {@code @followers} token) from a body of text,
 *       returned with their start/end offsets so a client can render a
 *       highlighted preview without re-parsing.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mentions")
@RequiredArgsConstructor
public class MentionController {

    private final MentionSuggestionService suggestions;
    private final UserActivityService activityService;

    @GetMapping("/suggest")
    public ResponseEntity<List<Suggestion>> suggest(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit,
            @AuthenticationPrincipal User user) {
        UUID viewerId = user != null ? user.getId() : null;
        List<Suggestion> hits = suggestions.suggest(q, limit, viewerId);
        if (viewerId != null) {
            activityService.recordMentionLookup(viewerId, q, null, hits.size());
        }
        return ResponseEntity.ok(hits);
    }

    /**
     * Lock-in a clicked mention — called by the front-end when the user
     * actually selects a candidate from the picker. Records the chosen
     * target so the activity row can be rebuilt as a "you mentioned @x".
     */
    @PostMapping("/click")
    public ResponseEntity<Map<String, Object>> click(
            @RequestBody ClickRequest req,
            @AuthenticationPrincipal User user) {
        if (user == null || req == null || req.targetUserId() == null) {
            return ResponseEntity.ok(Map.of("recorded", false));
        }
        activityService.recordMentionLookup(user.getId(), req.query(), req.targetUserId(), 1);
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    public record ClickRequest(String query, UUID targetUserId) {}

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parse(@RequestBody ParseRequest req) {
        if (req == null || req.text() == null) {
            return ResponseEntity.ok(Map.of("usernames", List.of(), "followers", false, "tokens", List.of()));
        }
        var parsed = MentionExtractor.extract(req.text());
        var tokens = MentionExtractor.tokens(req.text());
        return ResponseEntity.ok(Map.of(
                "usernames", parsed.getUsernames(),
                "followers", parsed.isHasFollowersToken(),
                "tokens", tokens
        ));
    }

    public record ParseRequest(String text) {}
}
