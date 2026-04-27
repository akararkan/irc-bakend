package ak.dev.irc.app.activity.controller;

import ak.dev.irc.app.activity.dto.RecordReelViewRequest;
import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.activity.service.ReelViewService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReelViewController {

    private final ReelViewService reelViewService;

    // Record a watched reel
    @PostMapping("/api/v1/posts/{postId}/reels/view")
    public ResponseEntity<ReelViewResponse> recordWatch(
            @PathVariable UUID postId,
            @Valid @RequestBody(required = false) RecordReelViewRequest req,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Integer watchedSeconds = req != null ? req.getWatchedSeconds() : null;
        ReelViewResponse response = reelViewService.recordWatch(user.getId(), postId, watchedSeconds);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // List my watched reels
    @GetMapping("/api/v1/users/me/reels/watched")
    public ResponseEntity<Page<ReelViewResponse>> listMyWatched(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(reelViewService.listMyWatched(user.getId(), pageable));
    }

    // Delete one watch entry
    @DeleteMapping("/api/v1/users/me/reels/watched/{reelViewId}")
    public ResponseEntity<Void> deleteOne(
            @PathVariable UUID reelViewId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        reelViewService.deleteOne(user.getId(), reelViewId);
        return ResponseEntity.noContent().build();
    }

    // Clear all watch history
    @DeleteMapping("/api/v1/users/me/reels/watched")
    public ResponseEntity<Map<String, Object>> deleteAll(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int deleted = reelViewService.deleteAll(user.getId());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}
