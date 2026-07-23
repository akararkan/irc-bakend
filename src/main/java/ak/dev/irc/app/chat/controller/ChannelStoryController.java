package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.CreateChannelStoryRequest;
import ak.dev.irc.app.chat.dto.response.ChannelStoryTrayItem;
import ak.dev.irc.app.chat.service.ChannelStoryService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Channel stories (Telegram-style): admins post stories AS the channel;
 * subscribers see them in the channel-story tray and on the channel profile.
 * Views, viewer log, polls, votes and the 8/16/24h auto-expiry reuse the
 * normal story endpoints ({@code /stories/{id}/views}, {@code /polls/*}) —
 * the channel's id is the story "author".
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ChannelStoryController {

    private final ChannelStoryService channelStoryService;
    private final ak.dev.irc.app.chat.service.ChannelHighlightService channelHighlightService;

    /** Post a story as the channel (JSON — media already uploaded). */
    @PostMapping(value = "/channels/{id}/stories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoryByAuthorEntity> post(@PathVariable UUID id,
                                                    @Valid @RequestBody CreateChannelStoryRequest req,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelStoryService.post(id, requireId(user), req));
    }

    /** Post a story as the channel (multipart — uploads media to R2). */
    @PostMapping(value = "/channels/{id}/stories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoryByAuthorEntity> postMultipart(
            @PathVariable UUID id,
            @RequestParam(required = false) String storyType,
            @RequestParam(required = false) String textContent,
            @RequestParam(required = false) Integer lifetimeHours,
            @RequestPart(value = "media", required = false) MultipartFile media,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelStoryService.postMultipart(id, requireId(user), storyType,
                        textContent, lifetimeHours, media, thumbnail));
    }

    /** The channel's active stories (public channel: anyone; private: subscribers). */
    @GetMapping("/channels/{id}/stories")
    public ResponseEntity<List<StoryByAuthorEntity>> list(@PathVariable UUID id,
                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelStoryService.list(id, requireId(user)));
    }

    @DeleteMapping("/channels/{id}/stories/{storyId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID storyId,
                                       @AuthenticationPrincipal User user) {
        channelStoryService.delete(id, storyId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Attach an A/B poll to a channel story (votes via the normal /polls/* API). */
    @PostMapping("/channels/{id}/stories/{storyId}/poll")
    public ResponseEntity<StoryPollEntity> createPoll(@PathVariable UUID id,
                                                      @PathVariable UUID storyId,
                                                      @RequestBody CreatePollRequest req,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelStoryService.createPoll(id, storyId, requireId(user),
                        req.question(), req.optionA(), req.optionB()));
    }

    public record CreatePollRequest(String question, String optionA, String optionB) {}

    /** My channel-story tray: subscribed channels with live stories. */
    @GetMapping("/channels/stories/tray")
    public ResponseEntity<List<ChannelStoryTrayItem>> tray(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelStoryService.tray(requireId(user)));
    }

    // ── Highlights (permanent curated story archives on the channel profile) ─────

    public record CreateHighlightRequest(String title, String coverUrl) {}
    public record ReorderHighlightsRequest(List<UUID> order) {}

    @PostMapping("/channels/{id}/highlights")
    public ResponseEntity<ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity> createHighlight(
            @PathVariable UUID id,
            @RequestBody CreateHighlightRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelHighlightService.create(id, requireId(user), req.title(), req.coverUrl()));
    }

    /** The channel's highlight rail (audience-gated like stories). */
    @GetMapping("/channels/{id}/highlights")
    public ResponseEntity<List<ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity>> highlights(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelHighlightService.list(id, requireId(user)));
    }

    /** Snapshot a live channel story into a highlight (survives the story TTL). */
    @PostMapping("/channels/{id}/highlights/{highlightId}/stories/{storyId}")
    public ResponseEntity<ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity> addToHighlight(
            @PathVariable UUID id,
            @PathVariable UUID highlightId,
            @PathVariable UUID storyId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelHighlightService.addStory(id, highlightId, storyId, requireId(user)));
    }

    @GetMapping("/channels/{id}/highlights/{highlightId}/stories")
    public ResponseEntity<List<ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity>> highlightStories(
            @PathVariable UUID id,
            @PathVariable UUID highlightId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelHighlightService.storiesIn(id, highlightId, requireId(user)));
    }

    @DeleteMapping("/channels/{id}/highlights/{highlightId}/stories/{storyId}")
    public ResponseEntity<Void> removeFromHighlight(@PathVariable UUID id,
                                                    @PathVariable UUID highlightId,
                                                    @PathVariable UUID storyId,
                                                    @AuthenticationPrincipal User user) {
        channelHighlightService.removeStory(id, highlightId, storyId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/channels/{id}/highlights/{highlightId}")
    public ResponseEntity<Void> deleteHighlight(@PathVariable UUID id,
                                                @PathVariable UUID highlightId,
                                                @AuthenticationPrincipal User user) {
        channelHighlightService.deleteHighlight(id, highlightId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Re-order the highlight rail (full id list, left-to-right). */
    @PatchMapping("/channels/{id}/highlights/order")
    public ResponseEntity<List<ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity>> reorderHighlights(
            @PathVariable UUID id,
            @RequestBody ReorderHighlightsRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelHighlightService.reorder(id, requireId(user), req.order()));
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
