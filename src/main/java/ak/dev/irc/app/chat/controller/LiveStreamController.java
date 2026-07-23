package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.LiveChatRequest;
import ak.dev.irc.app.chat.dto.request.StartStreamRequest;
import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.service.LiveStreamService;
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
 * Live streaming. The server owns lifecycle, the live viewer registry, discovery,
 * and live chat over the SSE stream; audio/video is ingested to / played from an
 * external media server via the stream key. Realtime events: {@code stream.started},
 * {@code stream.ended}, {@code stream.viewer}, {@code stream.chat}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class LiveStreamController {

    private final LiveStreamService streamService;

    /** Go live — the response carries the host-only ingest URL. */
    @PostMapping("/streams")
    public ResponseEntity<LiveStreamResponse> start(@Valid @RequestBody StartStreamRequest req,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(streamService.start(requireId(user), req));
    }

    /** Discover currently-live streams (most-watched first). */
    @GetMapping("/streams/live")
    public ResponseEntity<List<LiveStreamResponse>> live() {
        return ResponseEntity.ok(streamService.listLive());
    }

    @GetMapping("/streams/{id}")
    public ResponseEntity<LiveStreamResponse> get(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.get(id, requireId(user)));
    }

    @PostMapping("/streams/{id}/end")
    public ResponseEntity<Void> end(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        streamService.end(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Join as a viewer (registers presence, returns the playback URL). */
    @PostMapping("/streams/{id}/join")
    public ResponseEntity<LiveStreamResponse> join(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.join(id, requireId(user)));
    }

    @PostMapping("/streams/{id}/leave")
    public ResponseEntity<Void> leave(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        streamService.leave(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /** Send a live-chat line to the stream's viewers. */
    @PostMapping("/streams/{id}/chat")
    public ResponseEntity<Void> chat(@PathVariable UUID id,
                                     @Valid @RequestBody LiveChatRequest req,
                                     @AuthenticationPrincipal User user) {
        streamService.chat(id, requireId(user), req);
        return ResponseEntity.ok().build();
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
