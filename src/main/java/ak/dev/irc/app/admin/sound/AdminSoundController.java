package ak.dev.irc.app.admin.sound;

import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.common.util.Pages;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sound-library administration (sound-library.md §6, blueprint §3.3). The
 * review queue this exposes never existed — approve was the only reachable
 * transition, from a stray route outside the admin prefix (re-homed here).
 */
@RestController
@RequestMapping("/api/v1/admin/sounds")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: sound approval sits in content moderation
public class AdminSoundController {

    private final AdminSoundService adminSoundService;
    private final ak.dev.irc.app.post.cassandra.service.SoundAdoptionCounter soundAdoptionCounter;
    private final ak.dev.irc.app.post.cassandra.repository.SoundRepository soundRepository;

    public record ReasonBody(@Size(max = 500) String reason) {
    }

    public record RejectBody(@Size(max = 100) String reasonCode, @Size(max = 500) String note) {
    }

    public record TakedownBody(@Size(max = 500) String reason, Boolean muteAdoptingPosts,
                               String rightsClaimId) {
    }

    public record CategoryBody(String category) {
    }

    public record EditBody(@Size(max = 200) String title, @Size(max = 200) String artistName,
                           String coverArtUrl) {
    }

    public record TrendingExcludeBody(boolean excluded) {
    }

    public record ImportBody(List<AdminSoundService.ImportItem> items) {
    }

    public record CreateBody(@Size(max = 200) String title, @Size(max = 200) String artistName,
                             String audioUrl, String coverArtUrl, Integer durationSeconds,
                             String category, Boolean official) {
    }

    // ── reads ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<AdminSoundService.AdminSoundRow>> queue(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status,
            @RequestParam(required = false) UUID uploaderId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(adminSoundService.queue(status, uploaderId,
                cursor == null ? null : cursor.toInstant(ZoneOffset.UTC), Pages.clamp(pageSize)));
    }

    @GetMapping("/status-counts")
    public ResponseEntity<Map<String, Long>> statusCounts() {
        return ResponseEntity.ok(adminSoundService.statusCounts());
    }

    @GetMapping("/uploaders/{userId}")
    public ResponseEntity<List<AdminSoundService.AdminSoundRow>> uploaderHistory(
            @PathVariable UUID userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(adminSoundService.uploaderHistory(userId,
                cursor == null ? null : cursor.toInstant(ZoneOffset.UTC), Pages.clamp(pageSize)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminSoundService.AdminSoundDetail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(adminSoundService.detail(id));
    }

    /**
     * The only way a new sound enters the library (sound-library.md §5). Sounds
     * are admin-curated, not user-uploaded, so this lands straight on APPROVED —
     * there is nothing to review after the fact. {@code official} defaults to
     * {@code false}; set it for first-party/licensed audio you want flagged as
     * such in the picker.
     */
    @PostMapping
    public ResponseEntity<AdminSoundService.AdminSoundRow> create(@RequestBody CreateBody body) {
        return ResponseEntity.status(201).body(adminSoundService.create(
                body.title(), body.artistName(), body.audioUrl(), body.coverArtUrl(),
                body.durationSeconds(), body.category(), Boolean.TRUE.equals(body.official())));
    }

    /**
     * Multipart variant of {@link #create}: upload the audio bytes directly
     * (part {@code file}, plus optional {@code cover} image) instead of
     * pasting a URL. Same semantics — admin-curated, lands straight on
     * APPROVED; the stored row plays through the media proxy.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AdminSoundService.AdminSoundRow> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestParam @Size(max = 200) String title,
            @RequestParam(required = false) @Size(max = 200) String artistName,
            @RequestParam(required = false) Integer durationSeconds,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean official) {
        return ResponseEntity.status(201).body(adminSoundService.createFromUpload(
                file, cover, title, artistName, durationSeconds, category, official));
    }

    // ── transitions ─────────────────────────────────────────────────────

    /** Re-homed alias of the stray {@code POST /api/v1/sounds/{id}/approve}. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        adminSoundService.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @RequestBody(required = false) RejectBody body) {
        adminSoundService.reject(id,
                body != null ? body.reasonCode() : null,
                body != null ? body.note() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id,
                                        @RequestBody(required = false) ReasonBody body) {
        adminSoundService.archive(id, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id,
                                        @RequestBody(required = false) ReasonBody body) {
        adminSoundService.restore(id, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/takedown")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> takedown(@PathVariable UUID id,
                                                        @RequestBody(required = false) TakedownBody body) {
        int muted = adminSoundService.takedown(id,
                body != null ? body.reason() : null,
                body != null && Boolean.TRUE.equals(body.muteAdoptingPosts()),
                body != null ? body.rightsClaimId() : null);
        return ResponseEntity.ok(Map.of("mutedPosts", muted));
    }

    @PostMapping("/{id}/category")
    public ResponseEntity<Void> recategorize(@PathVariable UUID id, @RequestBody CategoryBody body) {
        adminSoundService.recategorize(id, body.category());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AdminSoundService.AdminSoundRow> edit(@PathVariable UUID id,
                                                                @RequestBody EditBody body) {
        return ResponseEntity.ok(adminSoundService.edit(id, body.title(), body.artistName(),
                body.coverArtUrl()));
    }

    @DeleteMapping("/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> hardDelete(@PathVariable UUID id,
                                           @RequestBody(required = false) ReasonBody body) {
        adminSoundService.hardDelete(id, body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/trending-exclude")
    public ResponseEntity<Void> trendingExclude(@PathVariable UUID id,
                                                @RequestBody TrendingExcludeBody body) {
        adminSoundService.setTrendingExcluded(id, body.excluded());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<List<AdminSoundService.AdminSoundRow>> importOfficial(
            @RequestBody ImportBody body) {
        return ResponseEntity.status(201).body(adminSoundService.importOfficial(body.items()));
    }

    /** Usage-based trending sounds over a rolling window (sound_adoptions_by_day). */
    @GetMapping("/trending")
    public ResponseEntity<List<java.util.Map<String, Object>>> trending(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        var top = soundAdoptionCounter.top(days,
                ak.dev.irc.app.common.util.Pages.clamp(limit));
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>(top.size());
        int rank = 1;
        for (var entry : top) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("soundId", entry.getKey());
            row.put("adoptions", entry.getValue());
            soundRepository.findById(entry.getKey())
                    .ifPresent(s -> row.put("title", s.getTitle()));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}
