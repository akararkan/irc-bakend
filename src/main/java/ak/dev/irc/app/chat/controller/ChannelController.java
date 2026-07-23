package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.CreateChannelRequest;
import ak.dev.irc.app.chat.dto.response.ChannelResponse;
import ak.dev.irc.app.chat.service.ChannelService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Telegram-style broadcast channels. Create a channel, discover/look-up public
 * channels, and subscribe/unsubscribe. Admins post and everyone reads via the
 * normal conversation/message endpoints using the channel's id.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ChannelController {

    private final ChannelService channelService;

    @PostMapping("/channels")
    public ResponseEntity<ChannelResponse> create(@Valid @RequestBody CreateChannelRequest req,
                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(channelService.create(requireId(user), req));
    }

    /** Discover public channels (optionally filtered by {@code q}), most-subscribed first. */
    @GetMapping("/channels/discover")
    public ResponseEntity<List<ChannelResponse>> discover(@RequestParam(required = false) String q,
                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.discover(requireId(user), q));
    }

    @GetMapping("/channels/by-handle/{handle}")
    public ResponseEntity<ChannelResponse> byHandle(@PathVariable String handle,
                                                   @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.getByHandle(handle, requireId(user)));
    }

    @PostMapping("/channels/{id}/subscribe")
    public ResponseEntity<ChannelResponse> subscribe(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(channelService.subscribe(id, requireId(user)));
    }

    @DeleteMapping("/channels/{id}/subscribe")
    public ResponseEntity<Void> unsubscribe(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        channelService.unsubscribe(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
