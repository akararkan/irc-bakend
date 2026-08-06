package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.ChannelAdminRequest;
import ak.dev.irc.app.chat.dto.request.CreateChannelRequest;
import ak.dev.irc.app.chat.dto.request.MarkViewsRequest;
import ak.dev.irc.app.chat.dto.request.UpdateChannelRequest;
import ak.dev.irc.app.chat.dto.response.ChannelAdminResponse;
import ak.dev.irc.app.chat.dto.response.ChannelResponse;
import ak.dev.irc.app.chat.dto.response.ChannelStatsResponse;
import ak.dev.irc.app.chat.dto.response.JoinRequestResponse;
import ak.dev.irc.app.chat.enums.JoinRequestStatus;
import ak.dev.irc.app.chat.service.ChannelJoinRequestService;
import ak.dev.irc.app.chat.service.ChannelPostMetricsService;
import ak.dev.irc.app.chat.service.ChannelService;
import ak.dev.irc.app.chat.service.ChannelStatsService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.ChannelStreamMessages;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Telegram-style broadcast channels. Create/update a channel, manage its photo,
 * cover, settings, admins, join requests and statistics; discover/look-up public
 * channels; subscribe/unsubscribe. Admins post and everyone reads via the
 * normal conversation/message endpoints using the channel's id.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelJoinRequestService joinRequestService;
    private final ChannelStatsService statsService;
    private final ChannelPostMetricsService metricsService;
    private final ak.dev.irc.app.chat.service.ChannelDiscussionService discussionService;

    // ── Create / read / update ───────────────────────────────────────────────────

    @PostMapping("/channels")
    public ResponseEntity<ChannelResponse> create(@Valid @RequestBody CreateChannelRequest req,
                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(channelService.create(requireId(user), req));
    }

    /** Discover public channels (optionally filtered by {@code q} and/or
     *  {@code category}), most-subscribed first. */
    @GetMapping("/channels/discover")
    public ResponseEntity<List<ChannelResponse>> discover(@RequestParam(required = false) String q,
                                                          @RequestParam(required = false) String category,
                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.discover(requireId(user), q, category));
    }

    @GetMapping("/channels/{id}")
    public ResponseEntity<ChannelResponse> get(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.get(id, requireId(user)));
    }

    @GetMapping("/channels/by-handle/{handle}")
    public ResponseEntity<ChannelResponse> byHandle(@PathVariable String handle,
                                                   @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.getByHandle(handle, requireId(user)));
    }

    /** Update title/description/@handle/public-private/category/settings. */
    @PatchMapping("/channels/{id}")
    public ResponseEntity<ChannelResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateChannelRequest req,
                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.update(id, requireId(user), req));
    }

    // ── Photo / cover ────────────────────────────────────────────────────────────

    @PostMapping(value = "/channels/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChannelResponse> setPhoto(@PathVariable UUID id,
                                                    @RequestPart("file") MultipartFile file,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.setPhoto(id, requireId(user), file));
    }

    @DeleteMapping("/channels/{id}/photo")
    public ResponseEntity<Void> deletePhoto(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        channelService.deletePhoto(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/channels/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChannelResponse> setCover(@PathVariable UUID id,
                                                    @RequestPart("file") MultipartFile file,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.setCover(id, requireId(user), file));
    }

    @DeleteMapping("/channels/{id}/cover")
    public ResponseEntity<Void> deleteCover(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        channelService.deleteCover(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Verified badge (platform admins only) ────────────────────────────────────

    /**
     * @deprecated stray admin route outside {@code /api/v1/admin/**} — misses
     * the filter-chain double gate. Re-homed as
     * {@code PATCH /api/v1/admin/channels/{id}/verified}; this alias answers
     * with a {@code Deprecation} header until clients migrate.
     */
    @Deprecated
    @PutMapping("/channels/{id}/verified")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ChannelResponse> setVerified(@PathVariable UUID id,
                                                       @RequestParam boolean verified) {
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Link", "</api/v1/admin/channels/" + id + "/verified>; rel=\"successor-version\"")
                .body(channelService.setVerified(id, verified));
    }

    // ── Subscribe / unsubscribe ──────────────────────────────────────────────────

    @PostMapping("/channels/{id}/subscribe")
    public ResponseEntity<ChannelResponse> subscribe(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.subscribe(id, requireId(user)));
    }

    @DeleteMapping("/channels/{id}/subscribe")
    public ResponseEntity<Void> unsubscribe(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        channelService.unsubscribe(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Admins ───────────────────────────────────────────────────────────────────

    @GetMapping("/channels/{id}/admins")
    public ResponseEntity<List<ChannelAdminResponse>> admins(@PathVariable UUID id,
                                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.listAdmins(id, requireId(user)));
    }

    /** Promote a subscriber to admin / update an admin's granular rights. */
    @PutMapping("/channels/{id}/admins/{userId}")
    public ResponseEntity<ChannelAdminResponse> putAdmin(@PathVariable UUID id,
                                                         @PathVariable UUID userId,
                                                         @RequestBody(required = false) @Valid ChannelAdminRequest req,
                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.putAdmin(id, requireId(user), userId, req));
    }

    @DeleteMapping("/channels/{id}/admins/{userId}")
    public ResponseEntity<Void> removeAdmin(@PathVariable UUID id,
                                            @PathVariable UUID userId,
                                            @AuthenticationPrincipal User user) {
        channelService.removeAdmin(id, requireId(user), userId);
        return ResponseEntity.noContent().build();
    }

    // ── Join requests ────────────────────────────────────────────────────────────

    @GetMapping("/channels/{id}/join-requests")
    public ResponseEntity<Page<JoinRequestResponse>> joinRequests(
            @PathVariable UUID id,
            @RequestParam(required = false) JoinRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(joinRequestService.list(id, requireId(user), status,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))));
    }

    @PostMapping("/channels/{id}/join-requests/{userId}/approve")
    public ResponseEntity<JoinRequestResponse> approve(@PathVariable UUID id,
                                                       @PathVariable UUID userId,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(joinRequestService.approve(id, requireId(user), userId));
    }

    @PostMapping("/channels/{id}/join-requests/{userId}/reject")
    public ResponseEntity<JoinRequestResponse> reject(@PathVariable UUID id,
                                                      @PathVariable UUID userId,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(joinRequestService.reject(id, requireId(user), userId));
    }

    // ── Discussion group & comments ──────────────────────────────────────────────

    /** Link a GROUP as this channel's discussion group (comments live there). */
    @PutMapping("/channels/{id}/discussion-group/{groupId}")
    public ResponseEntity<Void> linkDiscussion(@PathVariable UUID id,
                                               @PathVariable UUID groupId,
                                               @AuthenticationPrincipal User user) {
        discussionService.link(id, requireId(user), groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/channels/{id}/discussion-group")
    public ResponseEntity<Void> unlinkDiscussion(@PathVariable UUID id,
                                                 @AuthenticationPrincipal User user) {
        discussionService.unlink(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** A post's comment thread (newest first; cursor = last comment id). */
    @GetMapping("/channels/{id}/posts/{postId}/comments")
    public ResponseEntity<List<ak.dev.irc.app.chat.dto.response.MessageResponse>> comments(
            @PathVariable UUID id,
            @PathVariable long postId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "30") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(discussionService.comments(id, postId, requireId(user), before,
                ak.dev.irc.app.common.util.Pages.clamp(limit)));
    }

    /** Comment on a post — auto-joins you into the discussion group. */
    @PostMapping("/channels/{id}/posts/{postId}/comments")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.MessageResponse> addComment(
            @PathVariable UUID id,
            @PathVariable long postId,
            @Valid @RequestBody ak.dev.irc.app.chat.dto.request.SendMessageRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(discussionService.addComment(id, postId, requireId(user), req));
    }

    // ── Views / statistics ───────────────────────────────────────────────────────

    /** Batch view marker — counts each viewer once per post (Telegram's 👁 counter). */
    @PostMapping("/channels/{id}/posts/views")
    public ResponseEntity<Map<String, Object>> markViews(@PathVariable UUID id,
                                                         @Valid @RequestBody MarkViewsRequest req,
                                                         @AuthenticationPrincipal User user) {
        UUID userId = requireId(user);
        // Membership is enforced by requiring the channel detail to resolve for
        // the caller (throws NOT_A_MEMBER for private channels they can't read).
        ChannelResponse channel = channelService.get(id, userId);
        if (!channel.subscribed() && !channel.publicChannel()) {
            return ResponseEntity.ok(Map.of("counted", List.of()));
        }
        List<Long> counted = metricsService.markViewed(id, userId, req.getMessageIds());
        return ResponseEntity.ok(Map.of("counted", counted));
    }

    /** Owner/admin analytics. */
    @GetMapping("/channels/{id}/stats")
    public ResponseEntity<ChannelStatsResponse> stats(@PathVariable UUID id,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(statsService.stats(id, requireId(user)));
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException(ChannelStreamMessages.AUTH_REQUIRED_MSG);
        return user.getId();
    }
}
