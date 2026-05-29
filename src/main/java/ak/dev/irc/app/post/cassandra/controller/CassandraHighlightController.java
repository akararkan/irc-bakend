package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraHighlightService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/highlights")
@RequiredArgsConstructor
public class CassandraHighlightController {

    private final CassandraHighlightService highlightService;

    public record CreateHighlightRequest(UUID authorId, String title, String coverUrl,
                                         int displayOrder) {}

    @PostMapping
    public HighlightByAuthorEntity create(@RequestBody CreateHighlightRequest req) {
        return highlightService.createHighlight(req.authorId(), req.title(),
                                                req.coverUrl(), req.displayOrder());
    }

    @GetMapping("/by-author/{authorId}")
    public List<HighlightByAuthorEntity> listFor(@PathVariable UUID authorId) {
        return highlightService.listFor(authorId);
    }

    @PostMapping("/{highlightId}/stories/{storyId}")
    public ResponseEntity<StoryInHighlightEntity> addStory(@PathVariable UUID highlightId,
                                                           @PathVariable UUID storyId,
                                                           @RequestParam UUID requesterId) {
        return highlightService.addStoryToHighlight(highlightId, storyId, requesterId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{highlightId}/stories")
    public List<StoryInHighlightEntity> storiesIn(@PathVariable UUID highlightId) {
        return highlightService.storiesIn(highlightId);
    }

    @DeleteMapping("/{highlightId}/stories/{storyId}")
    public ResponseEntity<Void> removeStory(@PathVariable UUID highlightId,
                                            @PathVariable UUID storyId,
                                            @RequestParam Instant createdAt) {
        highlightService.removeStoryFromHighlight(highlightId, createdAt, storyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Re-order the authenticated user's highlights to match the supplied
     * sequence. Pass the full list of highlight ids in the desired
     * left-to-right order; foreign or unknown ids are silently skipped.
     * Re-running with the same list is a no-op.
     *
     * <p>Example body: {@code { "order": ["h1-uuid", "h2-uuid", ...] }}.</p>
     */
    public record ReorderRequest(List<UUID> order) {}

    @PatchMapping("/order")
    public List<HighlightByAuthorEntity> reorder(@RequestBody ReorderRequest req,
                                                 @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        if (req == null || req.order() == null) {
            throw new ForbiddenException("order list is required");
        }
        return highlightService.reorderHighlights(user.getId(), req.order());
    }
}
