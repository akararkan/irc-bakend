package ak.dev.irc.app.post.controller;

import ak.dev.irc.app.post.dto.story.*;
import ak.dev.irc.app.post.realtime.StoryRealtimeService;
import ak.dev.irc.app.post.realtime.StoryTrayRealtimeService;
import ak.dev.irc.app.post.service.StoryService;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService             storyService;
    private final StoryRealtimeService     realtimeService;
    private final StoryTrayRealtimeService trayRealtimeService;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping("/text")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryResponse> createText(
            @Valid @RequestBody CreateTextStoryRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.status(201).body(storyService.createTextStory(req, me));
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryResponse> createMedia(
            @RequestPart("data") @Valid CreateMediaStoryRequest req,
            @RequestPart("media") MultipartFile media) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.status(201).body(storyService.createMediaStory(req, media, me));
    }

    @PostMapping("/share")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryResponse> shareToStory(
            @Valid @RequestBody ShareToStoryRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.status(201).body(storyService.shareToStory(req, me));
    }

    // ── Tray real-time stream (per-user) ─────────────────────────────────────

    /**
     * SSE stream for the authenticated user's story tray.
     * Fires a {@code new_story} event the moment a followed user posts,
     * and a {@code story_removed} event when they delete or it expires.
     * The client uses this to update the tray ring without polling.
     */
    @GetMapping(value = "/tray/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter streamTray() {
        UUID me = SecurityUtils.requireCurrentUserId();
        return trayRealtimeService.subscribe(me);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping("/tray")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<StoryTrayGroup>> getTray() {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(storyService.getStoryTray(me));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<StoryResponse>> getByAuthor(@PathVariable UUID userId) {
        UUID me = SecurityUtils.getCurrentUserId().orElse(null);
        return ResponseEntity.ok(storyService.getStoriesByAuthor(userId, me));
    }

    // ── Real-time stream ──────────────────────────────────────────────────────

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter stream(@PathVariable UUID id) {
        UUID me = SecurityUtils.getCurrentUserId().orElse(null);
        return realtimeService.subscribe(id, me);
    }

    // ── Interactions ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/view")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> recordView(
            @PathVariable UUID id,
            @RequestBody(required = false) RecordViewRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.recordView(id, me, req != null ? req.watchDurationMs() : 0);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> react(
            @PathVariable UUID id,
            @Valid @RequestBody ReactToStoryRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.reactToStory(id, me, req.emoji());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> reply(
            @PathVariable UUID id,
            @Valid @RequestBody ReplyToStoryRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.replyToStory(id, me, req.text());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/poll/vote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> vote(
            @PathVariable UUID id,
            @Valid @RequestBody VotePollRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.voteOnPoll(id, me, req.choice());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        storyService.deleteStory(id, me);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/viewers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<StoryViewerResponse>> viewers(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(storyService.getViewers(id, me, pageable));
    }

    /** View count breakdown by relationship — only for the story author. */
    @GetMapping("/{id}/views/breakdown")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StoryResponse.ViewBreakdown> viewBreakdown(@PathVariable UUID id) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(storyService.getViewBreakdown(id, me));
    }
}
