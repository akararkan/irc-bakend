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
 * Public mention helpers used by the front-end's compose box, plus the
 * owner-scoped mentions-of-me feed:
 *
 * <ul>
 *   <li>{@code GET /api/v1/mentions/suggest?q=ak&limit=10} — autocomplete
 *       candidates for a partial handle.</li>
 *   <li>{@code POST /api/v1/mentions/parse} — extract every {@code @username}
 *       (and the special {@code @followers} token) from a body of text,
 *       returned with their start/end offsets so a client can render a
 *       highlighted preview without re-parsing.</li>
 *   <li>{@code GET /api/v1/mentions/me} — everywhere I was {@code @}-mentioned
 *       (posts, comments, research, Q&amp;A), newest first, hydrated with the
 *       mentioning user and a ready-to-navigate deep link.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mentions")
@RequiredArgsConstructor
public class MentionController {

    private final MentionSuggestionService suggestions;
    private final UserActivityService activityService;
    private final ak.dev.irc.app.post.cassandra.repository.MentionByUserRepository mentionByUserRepo;
    private final ak.dev.irc.app.user.repository.UserRepository userRepository;

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

    /**
     * The unified mentions-of-me inbox: one row per direct {@code @mention}
     * of the caller across posts, comments, research (+ comments) and Q&amp;A.
     * Chat mentions are deliberately absent — they surface only as
     * {@code MESSAGE_MENTION} notifications, never in a browsable feed
     * (messages can be deleted or disappearing).
     *
     * <p>Keyset pagination: pass the last row's {@code mentionedAt} back as
     * {@code cursor} for the next page.</p>
     */
    @GetMapping("/me")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MentionOfMe>> myMentions(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) java.time.Instant cursor,
            @AuthenticationPrincipal User user) {
        UUID me = user != null ? user.getId()
                : ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null);
        if (me == null) return ResponseEntity.status(401).build();
        int safe = Math.max(1, Math.min(limit, 50));

        var rows = cursor == null
                ? mentionByUserRepo.firstPage(me, safe)
                : mentionByUserRepo.pageAfter(me, cursor, safe);
        if (rows.isEmpty()) return ResponseEntity.ok(List.of());

        // Hydrate every mentioning author in one round-trip. The profile
        // join-fetch variant is required or avatars come back null.
        java.util.Set<UUID> authorIds = new java.util.HashSet<>();
        rows.forEach(r -> { if (r.getAuthorId() != null) authorIds.add(r.getAuthorId()); });
        Map<UUID, User> authors = new java.util.HashMap<>();
        userRepository.findActiveWithProfileByIdIn(authorIds)
                .forEach(u -> authors.put(u.getId(), u));

        List<MentionOfMe> out = new java.util.ArrayList<>(rows.size());
        for (var r : rows) {
            String sourceType = r.getSourceType() == null ? "POST" : r.getSourceType();
            User author = r.getAuthorId() == null ? null : authors.get(r.getAuthorId());
            out.add(new MentionOfMe(
                    sourceType,
                    r.getPostId(),
                    r.getSourceParentId(),
                    r.getTextPreview(),
                    r.getCreatedAt(),
                    deepLinkOf(sourceType, r.getPostId(), r.getSourceParentId()),
                    author == null ? null : new MentionActor(
                            author.getId(), author.getUsername(),
                            author.getFullName(), author.getProfileImage())));
        }
        return ResponseEntity.ok(out);
    }

    /** Navigate to where the mention happened; nested sources land on their parent. */
    private static String deepLinkOf(String sourceType, UUID sourceId, UUID parentId) {
        return switch (sourceType) {
            case "POST"             -> "/posts/" + sourceId;
            case "POST_COMMENT"     -> parentId == null ? null : "/posts/" + parentId;
            case "RESEARCH"         -> "/researches/" + sourceId;
            case "RESEARCH_COMMENT" -> parentId == null ? null : "/researches/" + parentId;
            case "QUESTION"         -> "/questions/" + sourceId;
            case "QUESTION_ANSWER"  -> parentId == null ? null : "/questions/" + parentId;
            default                 -> null;
        };
    }

    public record MentionOfMe(String sourceType, UUID sourceId, UUID parentId,
                              String snippet, java.time.Instant mentionedAt,
                              String deepLink, MentionActor actor) {}

    public record MentionActor(UUID id, String username, String fullName, String avatarUrl) {}

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
