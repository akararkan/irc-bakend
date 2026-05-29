package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.post.cassandra.service.WhoToFollowService;
import ak.dev.irc.app.user.dto.response.*;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.service.UserSocialService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserSocialController {

    private final UserSocialService  socialService;
    private final WhoToFollowService whoToFollowService;

    // ── Follow / Unfollow ─────────────────────────────────────────────────────

    @PostMapping("/{id}/follow")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> follow(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.follow(id));
    }

    @DeleteMapping("/{id}/follow")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> unfollow(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.unfollow(id));
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<Page<UserResponse>> getFollowers(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(socialService.getFollowers(id, pageable));
    }

    @GetMapping("/{id}/following")
    public ResponseEntity<Page<UserResponse>> getFollowing(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(socialService.getFollowing(id, pageable));
    }

    // ── Block / Unblock ───────────────────────────────────────────────────────

    @PostMapping("/{id}/block")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> block(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.block(id));
    }

    @DeleteMapping("/{id}/block")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> unblock(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.unblock(id));
    }

    @GetMapping("/me/blocked")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<UserResponse>> getBlocked(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(socialService.getBlockedUsers(pageable));
    }

    // ── Restrict / Unrestrict ─────────────────────────────────────────────────

    @PostMapping("/{id}/restrict")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> restrict(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.restrict(id));
    }

    @DeleteMapping("/{id}/restrict")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialActionResponse> unrestrict(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.unrestrict(id));
    }

    @GetMapping("/me/restricted")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<UserResponse>> getRestricted(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(socialService.getRestrictedUsers(pageable));
    }

    // ── Social Status ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/social-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SocialStatusResponse> getSocialStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(socialService.getSocialStatus(id));
    }

    // ── Friend Suggestions ────────────────────────────────────────────────────

    /**
     * Friends-of-friends suggestions for the authenticated user, hydrated with
     * name, avatar, follower count and isFollowing flag. Falls back to
     * who-to-follow automatically when the user has no mutual connections yet.
     */
    @GetMapping("/me/suggestions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<FollowSuggestionResponse>> mySuggestions(
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(whoToFollowService.friendSuggestions(user.getId(), limit));
    }

    /**
     * Dismiss a suggestion — removes it from the caller's suggestion partition.
     * Idempotent: no-op if the suggestion no longer exists.
     */
    @DeleteMapping("/me/suggestions/{candidateId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> dismissSuggestion(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal User user) {
        whoToFollowService.dismiss(user.getId(), candidateId);
        return ResponseEntity.noContent().build();
    }

    // ── Who to Follow ─────────────────────────────────────────────────────────

    /**
     * Verified and popular accounts the caller doesn't yet follow — ordered by
     * account type (PLATFORM_OFFICIAL → VERIFIED_SCHOLAR → VERIFIED_RESEARCHER
     * → INSTITUTION → MEDIA → REGULAR) then follower count DESC.
     * Auth optional: anonymous callers get the global list without exclusions.
     */
    @GetMapping("/who-to-follow")
    public ResponseEntity<List<FollowSuggestionResponse>> whoToFollow(
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        UUID viewerId = user != null ? user.getId() : null;
        return ResponseEntity.ok(whoToFollowService.whoToFollow(viewerId, limit));
    }
}
