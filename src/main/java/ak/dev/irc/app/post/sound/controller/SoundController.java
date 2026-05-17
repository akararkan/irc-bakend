package ak.dev.irc.app.post.sound.controller;

import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;
import ak.dev.irc.app.post.sound.dto.AttachSoundRequest;
import ak.dev.irc.app.post.sound.dto.SoundResponse;
import ak.dev.irc.app.post.sound.dto.UploadSoundRequest;
import ak.dev.irc.app.post.sound.service.SoundService;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SoundController {

    private final SoundService soundService;

    // ── Library ───────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/sounds")
    public ResponseEntity<Page<SoundResponse>> browse(
            @RequestParam(required = false) SoundCategory category,
            @RequestParam(defaultValue = "") String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(soundService.browse(category, q, pageable));
    }

    @GetMapping("/api/v1/sounds/trending")
    public ResponseEntity<List<SoundResponse>> trending(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(soundService.getTrending(limit));
    }

    @GetMapping("/api/v1/sounds/recent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SoundResponse>> recent(
            @RequestParam(defaultValue = "20") int limit) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(soundService.getRecentlyUsed(me, limit));
    }

    @GetMapping("/api/v1/sounds/{id}")
    public ResponseEntity<SoundResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(soundService.getById(id));
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @PostMapping(value = "/api/v1/sounds/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SoundResponse> upload(
            @RequestPart("data") @Valid UploadSoundRequest req,
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(value = "cover", required = false) MultipartFile cover) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.status(201).body(soundService.uploadSound(req, audio, cover, me));
    }

    // ── Attach to posts ───────────────────────────────────────────────────────

    @PatchMapping("/api/v1/posts/{postId}/sound")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> attachToPost(
            @PathVariable UUID postId,
            @Valid @RequestBody AttachSoundRequest req) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        soundService.attachToPost(postId, req, me);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/posts/{postId}/sound")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> detachFromPost(@PathVariable UUID postId) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        soundService.detachFromPost(postId, me);
        return ResponseEntity.noContent().build();
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/sounds/pending")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<SoundResponse>> pending(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(soundService.browse(null, null, pageable)
            .map(s -> s)); // filtered by PENDING in the service browse overload
        // In production, add a dedicated getPending(Pageable) method
    }

    @PostMapping("/api/v1/admin/sounds/{id}/approve")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SoundResponse> approve(@PathVariable UUID id) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(soundService.approveSound(id, me));
    }

    @PostMapping("/api/v1/admin/sounds/{id}/reject")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SoundResponse> reject(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        UUID me = SecurityUtils.getCurrentUserId().orElseThrow();
        return ResponseEntity.ok(soundService.rejectSound(id, me, reason));
    }
}
