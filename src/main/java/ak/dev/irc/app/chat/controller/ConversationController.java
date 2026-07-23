package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.*;
import ak.dev.irc.app.chat.dto.response.ConversationResponse;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.service.ConversationService;
import ak.dev.irc.app.common.exception.BadRequestException;
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

import java.util.UUID;

/**
 * Conversations — the inbox and per-conversation metadata/state. Postgres-backed
 * lists use {@code page/size}; the message log (Cassandra) lives on
 * {@link MessageController}.
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ConversationController {

    private final ConversationService conversationService;

    /** My inbox, pinned first then newest activity. */
    @GetMapping
    public ResponseEntity<Page<ConversationResponse>> inbox(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(conversationService.inbox(requireId(user), pageable));
    }

    @GetMapping("/archived")
    public ResponseEntity<Page<ConversationResponse>> archived(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(conversationService.archived(requireId(user), pageable));
    }

    /** Create a DIRECT (get-or-create) or GROUP conversation. */
    @PostMapping
    public ResponseEntity<ConversationResponse> create(
            @Valid @RequestBody CreateConversationRequest req,
            @AuthenticationPrincipal User user) {
        UUID me = requireId(user);
        // Call the concrete methods through the proxy directly: createGroup is
        // @Transactional (must engage), createDirect is intentionally NON-transactional
        // (it catches a unique-constraint race outside any transaction). Routing via a
        // single create() dispatcher would self-invoke and bypass createGroup's proxy.
        if (req.getType() == ConversationType.GROUP) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(conversationService.createGroup(me, req));
        }
        if (req.getRecipientId() == null) {
            throw new BadRequestException("recipientId is required for a DIRECT conversation.");
        }
        return ResponseEntity.ok(conversationService.createDirect(me, req.getRecipientId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> get(@PathVariable UUID id,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(conversationService.get(id, requireId(user)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ConversationResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateConversationRequest req,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(conversationService.update(id, requireId(user), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        conversationService.delete(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id,
                                     @Valid @RequestBody ReadMarkerRequest req,
                                     @AuthenticationPrincipal User user) {
        conversationService.markRead(id, requireId(user), req.getLastReadMessageId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/mute")
    public ResponseEntity<Void> mute(@PathVariable UUID id,
                                     @RequestBody MuteRequest req,
                                     @AuthenticationPrincipal User user) {
        conversationService.mute(id, requireId(user), req.getMutedUntil());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<Void> pin(@PathVariable UUID id,
                                    @RequestBody PinRequest req,
                                    @AuthenticationPrincipal User user) {
        conversationService.pin(id, requireId(user), req.isPinned());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id,
                                        @RequestBody ArchiveRequest req,
                                        @AuthenticationPrincipal User user) {
        conversationService.archive(id, requireId(user), req.isArchived());
        return ResponseEntity.ok().build();
    }

    /** Mark a chat as unread (keeps it flagged unread in the inbox until next opened). */
    @PostMapping("/{id}/unread")
    public ResponseEntity<Void> markUnread(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        conversationService.markUnread(id, requireId(user));
        return ResponseEntity.ok().build();
    }

    /** Set the disappearing-messages timer ({@code seconds = 0} = off). */
    @PostMapping("/{id}/disappearing")
    public ResponseEntity<Void> disappearing(@PathVariable UUID id,
                                             @Valid @RequestBody DisappearingRequest req,
                                             @AuthenticationPrincipal User user) {
        conversationService.setDisappearing(id, requireId(user), req.getSeconds());
        return ResponseEntity.ok().build();
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
