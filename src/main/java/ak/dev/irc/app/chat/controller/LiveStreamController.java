package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.LiveChatRequest;
import ak.dev.irc.app.chat.dto.request.StartStreamRequest;
import ak.dev.irc.app.chat.dto.request.UpdateStreamRequest;
import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.dto.response.RecordingInfo;
import ak.dev.irc.app.chat.service.LiveStreamService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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

    /** The "following is live" row — streams from people you follow that are live
     *  now, most-recently-started first. Each card carries the host avatar + handle. */
    @GetMapping("/streams/live/following")
    public ResponseEntity<List<LiveStreamResponse>> followingLive(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.listFollowingLive(requireId(user)));
    }

    @GetMapping("/streams/{id}")
    public ResponseEntity<LiveStreamResponse> get(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.get(id, requireId(user)));
    }

    // ── Management (owner-only) ──────────────────────────────────────────────────

    /** My streams (LIVE + ENDED), newest-first — the manage/history list. */
    @GetMapping("/streams/mine")
    public ResponseEntity<Page<LiveStreamResponse>> mine(@AuthenticationPrincipal User user,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(streamService.listMine(requireId(user), page, size));
    }

    /** Edit a stream's title/description (owner-only). */
    @PatchMapping("/streams/{id}")
    public ResponseEntity<LiveStreamResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateStreamRequest req,
                                                     @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.update(id, requireId(user), req));
    }

    /** Delete a stream and its recording (ends it first if still live). */
    @DeleteMapping("/streams/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        streamService.delete(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Recording (owner-only) ───────────────────────────────────────────────────

    /**
     * Start or resume recording while the stream is LIVE. The host may do this as
     * often as they like during a broadcast; each take is written separately and
     * all of them are joined into one file when the stream ends.
     */
    @PostMapping("/streams/{id}/recording/start")
    public ResponseEntity<LiveStreamResponse> startRecording(@PathVariable UUID id,
                                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.startRecording(id, requireId(user)));
    }

    /** Pause recording, staying live. The broadcast and its viewers continue. */
    @PostMapping("/streams/{id}/recording/stop")
    public ResponseEntity<LiveStreamResponse> stopRecording(@PathVariable UUID id,
                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.stopRecording(id, requireId(user)));
    }

    /** Recording manifest — status + downloadable parts. */
    @GetMapping("/streams/{id}/recording")
    public ResponseEntity<RecordingInfo> recording(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(streamService.recording(id, requireId(user)));
    }

    /** Delete just the recording, keeping the stream record. */
    @DeleteMapping("/streams/{id}/recording")
    public ResponseEntity<Void> deleteRecording(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        streamService.deleteRecording(id, requireId(user));
        return ResponseEntity.noContent().build();
    }

    /**
     * Download the recording. With one part, {@code ?part=} is optional; with
     * several, name one (see {@code GET /streams/{id}/recording}). Streamed
     * straight from disk — O(bytes).
     */
    @GetMapping("/streams/{id}/recording/download")
    public ResponseEntity<Resource> downloadRecording(@PathVariable UUID id,
                                                      @RequestParam(required = false) String part,
                                                      @AuthenticationPrincipal User user) {
        Path file = streamService.resolveRecordingFile(id, requireId(user), part);
        String filename = file.getFileName().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(file.toFile().length())
                .body(new FileSystemResource(file));
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
