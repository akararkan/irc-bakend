package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.CallSignalRequest;
import ak.dev.irc.app.chat.dto.request.InitiateCallRequest;
import ak.dev.irc.app.chat.dto.response.CallResponse;
import ak.dev.irc.app.chat.service.CallService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Voice/video calls. Control plane only — the server rings invitees, tracks call
 * state, and blind-relays WebRTC SDP/ICE between peers over the SSE stream; the
 * media itself is peer-to-peer. Realtime events: {@code call.incoming},
 * {@code call.accepted}, {@code call.declined}, {@code call.ended},
 * {@code call.participant}, {@code call.signal}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CallController {

    private final CallService callService;

    /** Start a call in a conversation (rings every other active member). */
    @PostMapping("/conversations/{id}/calls")
    public ResponseEntity<CallResponse> initiate(@PathVariable UUID id,
                                                 @Valid @RequestBody InitiateCallRequest req,
                                                 @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(callService.initiate(id, requireId(user), req.getType()));
    }

    /** Current state of a call. */
    @GetMapping("/calls/{callId}")
    public ResponseEntity<CallResponse> get(@PathVariable UUID callId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(callService.get(callId, requireId(user)));
    }

    @PostMapping("/calls/{callId}/accept")
    public ResponseEntity<CallResponse> accept(@PathVariable UUID callId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(callService.accept(callId, requireId(user)));
    }

    @PostMapping("/calls/{callId}/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID callId, @AuthenticationPrincipal User user) {
        callService.decline(callId, requireId(user));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/calls/{callId}/end")
    public ResponseEntity<Void> end(@PathVariable UUID callId, @AuthenticationPrincipal User user) {
        callService.end(callId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Relay a WebRTC OFFER/ANSWER/ICE frame to one peer in the call. */
    @PostMapping("/calls/{callId}/signal")
    public ResponseEntity<Void> signal(@PathVariable UUID callId,
                                       @Valid @RequestBody CallSignalRequest req,
                                       @AuthenticationPrincipal User user) {
        callService.signal(callId, requireId(user), req);
        return ResponseEntity.ok().build();
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
