package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.PostBySoundEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundByCategoryEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraSoundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sound library (TikTok-style reusable audio). */
@RestController
@RequestMapping("/api/v1/sounds")
@RequiredArgsConstructor
public class CassandraSoundController {

    private final CassandraSoundService soundService;

    public record UploadSoundRequest(String title, String artistName, String audioUrl,
                                     String coverArtUrl, Integer durationSeconds,
                                     String category, UUID uploaderId, Boolean autoApprove) {}

    /** Upload a sound. autoApprove=true skips moderation (admin-only in prod). */
    @PostMapping
    public SoundEntity upload(@RequestBody UploadSoundRequest req) {
        return soundService.createSound(
                req.title(), req.artistName(), req.audioUrl(),
                req.coverArtUrl(), req.durationSeconds(), req.category(),
                req.uploaderId(), Boolean.TRUE.equals(req.autoApprove()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SoundEntity> get(@PathVariable UUID id) {
        SoundEntity s = soundService.getById(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    /** Mark a pending sound as APPROVED → publishes to the category browser. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        soundService.approve(id);
        return ResponseEntity.noContent().build();
    }

    /** Browse the library by category, cursor-paginated (newest first). */
    @GetMapping("/by-category/{category}")
    public List<SoundByCategoryEntity> listByCategory(@PathVariable String category,
                                                      @RequestParam(defaultValue = "20") int pageSize,
                                                      @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? soundService.listByCategory(category, pageSize)
                : soundService.listByCategoryAfter(category, cursor, pageSize);
    }

    /** "All posts using this sound" — the TikTok discover-page query. */
    @GetMapping("/{id}/posts")
    public List<PostBySoundEntity> postsUsing(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return soundService.postsUsingSound(id, pageSize);
    }

    /** Use count for trending UI ("used in N posts"). */
    @GetMapping("/{id}/usage")
    public Map<String, Object> usage(@PathVariable UUID id) {
        return Map.of("soundId", id, "useCount", soundService.useCountFor(id));
    }
}
