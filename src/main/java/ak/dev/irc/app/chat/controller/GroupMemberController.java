package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.*;
import ak.dev.irc.app.chat.dto.response.InviteLinkResponse;
import ak.dev.irc.app.chat.dto.response.MemberResponse;
import ak.dev.irc.app.chat.service.GroupMemberService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Group membership, roles, invite links, and joining. */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class GroupMemberController {

    private final GroupMemberService memberService;

    @GetMapping("/{id}/members")
    public ResponseEntity<Page<MemberResponse>> members(@PathVariable UUID id,
                                                        @AuthenticationPrincipal User user,
                                                        @PageableDefault(size = 30) Pageable pageable) {
        return ResponseEntity.ok(memberService.listMembers(id, requireId(user), pageable));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<Void> add(@PathVariable UUID id,
                                    @Valid @RequestBody AddMembersRequest req,
                                    @AuthenticationPrincipal User user) {
        memberService.addMembers(id, requireId(user), req.getUserIds());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID id,
                                       @PathVariable UUID userId,
                                       @AuthenticationPrincipal User user) {
        memberService.removeMember(id, requireId(user), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/{userId}/role")
    public ResponseEntity<Void> role(@PathVariable UUID id,
                                     @PathVariable UUID userId,
                                     @Valid @RequestBody ChangeRoleRequest req,
                                     @AuthenticationPrincipal User user) {
        memberService.changeRole(id, requireId(user), userId, req.getRole());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/members/{userId}/restrict")
    public ResponseEntity<Void> restrict(@PathVariable UUID id,
                                         @PathVariable UUID userId,
                                         @RequestBody RestrictMemberRequest req,
                                         @AuthenticationPrincipal User user) {
        memberService.restrictMember(id, requireId(user), userId, req.isRestricted());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leave(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        memberService.leave(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/transfer-owner")
    public ResponseEntity<Void> transfer(@PathVariable UUID id,
                                         @Valid @RequestBody TransferOwnerRequest req,
                                         @AuthenticationPrincipal User user) {
        memberService.transferOwnership(id, requireId(user), req.getNewOwnerId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/invite-link")
    public ResponseEntity<InviteLinkResponse> createInvite(@PathVariable UUID id,
                                                           @Valid @RequestBody(required = false) CreateInviteLinkRequest req,
                                                           @AuthenticationPrincipal User user) {
        CreateInviteLinkRequest body = req != null ? req : new CreateInviteLinkRequest();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(memberService.createInvite(id, requireId(user), body));
    }

    @DeleteMapping("/{id}/invite-link")
    public ResponseEntity<Void> revokeInvite(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        memberService.revokeInvite(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Multi-link manager (Telegram-style parallel invite links) ────────────────

    /** Create an additional link WITHOUT revoking the existing ones. */
    @PostMapping("/{id}/invite-links")
    public ResponseEntity<InviteLinkResponse> createAdditionalInvite(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CreateInviteLinkRequest req,
            @AuthenticationPrincipal User user) {
        CreateInviteLinkRequest body = req != null ? req : new CreateInviteLinkRequest();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(memberService.createAdditionalInvite(id, requireId(user), body));
    }

    /** All active links' metadata (tokens are shown only at creation). */
    @GetMapping("/{id}/invite-links")
    public ResponseEntity<List<ak.dev.irc.app.chat.dto.response.InviteLinkInfoResponse>> listInvites(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(memberService.listInvites(id, requireId(user)));
    }

    /** Revoke one specific link, leaving the others working. */
    @DeleteMapping("/{id}/invite-links/{inviteId}")
    public ResponseEntity<Void> revokeOneInvite(@PathVariable UUID id,
                                                @PathVariable UUID inviteId,
                                                @AuthenticationPrincipal User user) {
        memberService.revokeOneInvite(id, requireId(user), inviteId);
        return ResponseEntity.noContent().build();
    }

    /** Join a group/channel via an invite token. {@code status} is JOINED, or
     *  PENDING_APPROVAL when the link requires admin approval. */
    @PostMapping("/join")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.JoinByTokenResponse> join(
            @Valid @RequestBody JoinByTokenRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(memberService.join(requireId(user), req.getToken()));
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
