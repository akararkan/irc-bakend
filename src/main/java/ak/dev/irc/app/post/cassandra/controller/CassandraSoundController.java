package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.PostBySoundEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundByCategoryEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraSoundService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sound library (TikTok-style reusable audio). */
@RestController
@RequestMapping("/api/v1/sounds")
@RequiredArgsConstructor
public class CassandraSoundController {

    private final CassandraSoundService soundService;

    private static final Set<Role> MODERATION_ROLES = Set.of(Role.ADMIN, Role.MODERATOR);

    /** {@code uploaderId} is ignored — the uploader is always the authenticated caller. */
    public record UploadSoundRequest(String title, String artistName, String audioUrl,
                                     String coverArtUrl, Integer durationSeconds,
                                     String category, UUID uploaderId, Boolean autoApprove) {}

    /**
     * @deprecated Sounds are admin-curated only now — regular users can no
     * longer add to the library, closing what used to be an open, unmoderated
     * upload path into a picker every post/reel/story can use. Re-homed as
     * {@code POST /api/v1/admin/sounds}; this alias answers with a
     * {@code Deprecation} header until clients migrate. {@code ADMIN}/
     * {@code MODERATOR} only — a regular user's request is now rejected with
     * 403 rather than silently landing in PENDING_REVIEW.
     */
    @Deprecated
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    public ResponseEntity<SoundEntity> upload(@RequestBody UploadSoundRequest req,
                                              @AuthenticationPrincipal User user) {
        boolean privileged  = user.getRole() != null && MODERATION_ROLES.contains(user.getRole());
        boolean autoApprove = Boolean.TRUE.equals(req.autoApprove()) && privileged;
        SoundEntity created = soundService.createSound(
                req.title(), req.artistName(), req.audioUrl(),
                req.coverArtUrl(), req.durationSeconds(), req.category(),
                user.getId(), autoApprove);
        return ResponseEntity.status(201)
                .header("Deprecation", "true")
                .header("Link", "</api/v1/admin/sounds>; rel=\"successor-version\"")
                .body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SoundEntity> get(@PathVariable UUID id) {
        SoundEntity s = soundService.getById(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    /**
     * Sound-library search for the reels/stories sound picker — typo-tolerant
     * title/artist relevance ranking with a use-count popularity boost.
     * APPROVED sounds only; blank {@code q} returns {@code []} (use the
     * category browser for listing). Also reachable via the global
     * {@code /api/v1/search?types=SOUND}.
     */
    @GetMapping("/search")
    public List<SoundEntity> search(@RequestParam String q,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(defaultValue = "20") int limit) {
        return soundService.search(q, category, Math.min(Math.max(limit, 1), 100));
    }

    /**
     * Mark a pending sound as APPROVED → publishes to the category browser.
     *
     * @deprecated stray admin route outside {@code /api/v1/admin/**} (misses the
     * filter-chain double gate). Re-homed as
     * {@code POST /api/v1/admin/sounds/{id}/approve}; this alias answers with a
     * {@code Deprecation} header until clients migrate. The phantom
     * MODERATOR/SUPER_ADMIN grants were normalized to the real ADMIN role.
     */
    @Deprecated
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        soundService.approve(id);
        return ResponseEntity.noContent()
                .header("Deprecation", "true")
                .header("Link", "</api/v1/admin/sounds/" + id + "/approve>; rel=\"successor-version\"")
                .build();
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
