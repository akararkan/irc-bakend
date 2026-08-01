package ak.dev.irc.app.media.controller;

import ak.dev.irc.app.media.dto.MediaDtos.*;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.service.MediaAssetService;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Media pipeline surface (spec §20.4). The client requests an upload intent
 * (getting a presigned PUT URL), PUTs the bytes directly to storage, then calls
 * complete to trigger server-side re-encode/downscale. The tier hint arrives as
 * {@code X-Media-Tier} so the backend never produces renditions above it.
 */
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MediaUploadController {

    private final MediaAssetService mediaAssetService;

    @PostMapping("/upload-intent")
    public ResponseEntity<UploadIntentResponse> uploadIntent(
            @Valid @RequestBody UploadIntentRequest req,
            @RequestHeader(value = "X-Media-Tier", required = false) String tierHeader) {
        MediaTier tier = MediaTier.parse(tierHeader);
        return ResponseEntity.ok(
                mediaAssetService.uploadIntent(SecurityUtils.requireCurrentUserId(), req, tier));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID id) {
        mediaAssetService.complete(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaStatusResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(mediaAssetService.get(SecurityUtils.requireCurrentUserId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        mediaAssetService.delete(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
