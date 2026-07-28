package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.SendGiftRequest;
import ak.dev.irc.app.chat.dto.request.StreamReactionRequest;
import ak.dev.irc.app.chat.dto.response.*;
import ak.dev.irc.app.chat.service.StreamStageService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The multi-guest <b>stage</b> for a live stream (come up & talk beside the host,
 * mute, request/invite) plus live <b>reactions</b> and <b>gifts</b>. Sits beside
 * {@link LiveStreamController} on {@code /streams/**}; all realtime output rides the
 * shared per-user SSE stream ({@code stream.stage}, {@code stream.stage.request},
 * {@code stream.stage.invite}, {@code stream.stage.grant}, {@code stream.reaction},
 * {@code stream.gift}).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class StreamStageController {

    private final StreamStageService stageService;

    // ── Stage: read ──────────────────────────────────────────────────────────────

    /** The current stage roster (host + active guests). */
    @GetMapping("/streams/{id}/stage")
    public ResponseEntity<StageState> stage(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.stage(id, requireId(user)));
    }

    /** The host's pending hand-raise queue (host-only). */
    @GetMapping("/streams/{id}/stage/requests")
    public ResponseEntity<List<StageMember>> pendingRequests(@PathVariable UUID id,
                                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.pendingRequests(id, requireId(user)));
    }

    // ── Stage: viewer-driven ─────────────────────────────────────────────────────

    /** Raise your hand to come up. The host is notified. */
    @PostMapping("/streams/{id}/stage/requests")
    public ResponseEntity<StageMember> requestUp(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.requestUp(id, requireId(user)));
    }

    /** Accept the host's invite and come up. The response carries YOUR publish
     *  credentials — start your camera with them. */
    @PostMapping("/streams/{id}/stage/accept")
    public ResponseEntity<StageMember> accept(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.accept(id, requireId(user)));
    }

    /** Decline the host's invite. */
    @PostMapping("/streams/{id}/stage/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        stageService.declineInvite(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Step down off the stage yourself. */
    @PostMapping("/streams/{id}/stage/leave")
    public ResponseEntity<Void> leaveStage(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        stageService.leaveStage(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Stage: host-driven ───────────────────────────────────────────────────────

    /** Approve a hand-raise (host-only): the viewer comes up. */
    @PostMapping("/streams/{id}/stage/requests/{userId}/approve")
    public ResponseEntity<StageMember> approve(@PathVariable UUID id, @PathVariable UUID userId,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.approve(id, requireId(user), userId));
    }

    /** Deny a hand-raise (host-only). */
    @PostMapping("/streams/{id}/stage/requests/{userId}/deny")
    public ResponseEntity<Void> deny(@PathVariable UUID id, @PathVariable UUID userId,
                                     @AuthenticationPrincipal User user) {
        stageService.deny(id, requireId(user), userId);
        return ResponseEntity.noContent().build();
    }

    /** Invite a viewer up (host-only). They are notified and may accept/decline. */
    @PostMapping("/streams/{id}/stage/invites/{userId}")
    public ResponseEntity<Void> invite(@PathVariable UUID id, @PathVariable UUID userId,
                                       @AuthenticationPrincipal User user) {
        stageService.invite(id, requireId(user), userId);
        return ResponseEntity.noContent().build();
    }

    /** Take a guest off the stage (host-only). */
    @DeleteMapping("/streams/{id}/stage/{userId}")
    public ResponseEntity<Void> removeGuest(@PathVariable UUID id, @PathVariable UUID userId,
                                            @AuthenticationPrincipal User user) {
        stageService.removeGuest(id, requireId(user), userId);
        return ResponseEntity.noContent().build();
    }

    /** Mute a guest for everyone (host-only). */
    @PostMapping("/streams/{id}/stage/{userId}/mute")
    public ResponseEntity<Void> mute(@PathVariable UUID id, @PathVariable UUID userId,
                                     @AuthenticationPrincipal User user) {
        stageService.setMuted(id, requireId(user), userId, true);
        return ResponseEntity.noContent().build();
    }

    /** Unmute a guest (host-only). */
    @PostMapping("/streams/{id}/stage/{userId}/unmute")
    public ResponseEntity<Void> unmute(@PathVariable UUID id, @PathVariable UUID userId,
                                       @AuthenticationPrincipal User user) {
        stageService.setMuted(id, requireId(user), userId, false);
        return ResponseEntity.noContent().build();
    }

    // ── Reactions ────────────────────────────────────────────────────────────────

    /** Tap a floating reaction (default {@code LIKE}). Broadcast, never stored. */
    @PostMapping("/streams/{id}/reactions")
    public ResponseEntity<Void> react(@PathVariable UUID id,
                                      @RequestBody(required = false) StreamReactionRequest req,
                                      @AuthenticationPrincipal User user) {
        stageService.react(id, requireId(user), req);
        return ResponseEntity.noContent().build();
    }

    // ── Gifts (symbolic) ─────────────────────────────────────────────────────────

    /** The gift catalogue for the picker. */
    @GetMapping("/streams/gifts/catalog")
    public ResponseEntity<List<GiftCatalogEntry>> giftCatalog() {
        return ResponseEntity.ok(stageService.giftCatalog());
    }

    /** Send a symbolic gift. Returns the broadcast event (incl. your new total). */
    @PostMapping("/streams/{id}/gifts")
    public ResponseEntity<StreamGiftEvent> sendGift(@PathVariable UUID id,
                                                    @Valid @RequestBody SendGiftRequest req,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.sendGift(id, requireId(user), req));
    }

    /** The stream's "top supporters" leaderboard. */
    @GetMapping("/streams/{id}/gifts/top")
    public ResponseEntity<List<GiftSupporter>> topSupporters(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "10") int limit,
                                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(stageService.topSupporters(id, limit));
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
