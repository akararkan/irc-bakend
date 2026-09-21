package ak.dev.irc.app.media.controller;

import ak.dev.irc.app.media.dto.IngestResult;
import ak.dev.irc.app.media.service.UploadSessionService;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * Chunked/resumable uploads — the shared big-file path for every surface.
 * Flow: {@code POST /} (init, fail-fast policy) → {@code PUT /{id}/chunks/{n}}
 * (raw octet-stream, any order, retryable) → {@code POST /{id}/complete}
 * (assemble + full ingest through the media pipeline; returns the same media
 * ref shape as an inline multipart upload). {@code GET /{id}} lists received
 * chunks so a client resumes after a dropped connection instead of restarting.
 */
@RestController
@RequestMapping("/api/v1/uploads/sessions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class UploadSessionController {

    private final UploadSessionService sessions;

    public record InitRequest(@NotBlank String surface,
                              @NotBlank String fileName,
                              String mime,
                              @Positive long totalBytes) {}

    @PostMapping
    public ResponseEntity<UploadSessionService.InitResult> init(@Valid @RequestBody InitRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.init(
                SecurityUtils.requireCurrentUserId(),
                req.surface(), req.fileName(), req.mime(), req.totalBytes()));
    }

    @PutMapping(value = "/{id}/chunks/{index}", consumes = "application/octet-stream")
    public ResponseEntity<Void> putChunk(@PathVariable UUID id, @PathVariable int index,
                                         HttpServletRequest request) throws IOException {
        sessions.putChunk(SecurityUtils.requireCurrentUserId(), id, index, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UploadSessionService.SessionStatus> status(@PathVariable UUID id) {
        return ResponseEntity.ok(sessions.status(SecurityUtils.requireCurrentUserId(), id));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<IngestResult> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(sessions.complete(SecurityUtils.requireCurrentUserId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        sessions.cancel(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
