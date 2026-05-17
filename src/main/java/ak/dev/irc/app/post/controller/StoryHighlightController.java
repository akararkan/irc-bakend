package ak.dev.irc.app.post.controller;

import ak.dev.irc.app.post.dto.story.*;
import ak.dev.irc.app.post.service.StoryService;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/highlights")
@RequiredArgsConstructor
public class StoryHighlightController {

    private final StoryService storyService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryHighlightResponse> create(
            @Valid @RequestBody CreateHighlightRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.status(201).body(storyService.createHighlight(req, me));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryHighlightResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateHighlightRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(storyService.updateHighlight(id, req, me));
    }

    @PostMapping("/{id}/stories")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryHighlightResponse> addStory(
            @PathVariable UUID id,
            @RequestParam UUID storyId) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(storyService.addStoryToHighlight(id, storyId, me));
    }

    @DeleteMapping("/{id}/stories/{storyId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeStory(
            @PathVariable UUID id,
            @PathVariable UUID storyId) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.removeStoryFromHighlight(id, storyId, me);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.deleteHighlight(id, me);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<StoryHighlightResponse>> getByUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(storyService.getHighlights(userId));
    }

    @GetMapping("/{id}/stories")
    public ResponseEntity<Page<StoryResponse>> getStories(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(storyService.getHighlightStories(id, pageable));
    }
}
