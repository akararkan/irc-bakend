package ak.dev.irc.app.admin.content;

import ak.dev.irc.app.admin.moderation.PlatformKeyword;
import ak.dev.irc.app.admin.moderation.PlatformKeywordService;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.common.util.Pages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Content moderation over posts/comments/stories + the platform keyword
 * blocklist (blueprint §3.2). Post takedown is reversible
 * ({@code REMOVED → PUBLISHED}); comment and story deletion are irreversible
 * and therefore snapshot-first + step-up.
 */
@RestController
@RequestMapping("/api/v1/admin/content")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: content moderation is MODERATOR RW
public class AdminContentController {

    private final AdminContentService adminContentService;
    private final PlatformKeywordService platformKeywordService;

    public record ModerationActionRequest(@Size(max = 500) String reason, UUID reportId) {
    }

    public record KeywordRequest(@NotBlank String keyword,
                                 PlatformKeyword.Severity severity,
                                 @Size(max = 200) String note) {
    }

    public record KeywordTestRequest(@NotBlank String text) {
    }

    // ── posts ───────────────────────────────────────────────────────────

    @GetMapping("/posts")
    public ResponseEntity<List<AdminContentService.AdminPostRow>> posts(
            @RequestParam UUID authorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(adminContentService.postsByAuthor(
                authorId, status,
                cursor == null ? null : cursor.toInstant(ZoneOffset.UTC),
                Pages.clamp(pageSize)));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<AdminContentService.AdminPostRow> post(@PathVariable UUID postId) {
        return ResponseEntity.ok(adminContentService.post(postId));
    }

    @PostMapping("/posts/{postId}/remove")
    @RequiresStepUp
    public ResponseEntity<Void> removePost(@PathVariable UUID postId,
                                           @RequestBody(required = false) ModerationActionRequest body) {
        adminContentService.removePost(postId,
                body != null ? body.reason() : null,
                body != null ? body.reportId() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/restore")
    public ResponseEntity<Void> restorePost(@PathVariable UUID postId) {
        adminContentService.restorePost(postId);
        return ResponseEntity.noContent().build();
    }

    // ── comments / stories / highlight pins ─────────────────────────────

    @DeleteMapping("/comments/{commentId}")
    @RequiresStepUp
    public ResponseEntity<Void> deleteComment(@PathVariable UUID commentId,
                                              @RequestBody(required = false) ModerationActionRequest body) {
        adminContentService.deleteComment(commentId,
                body != null ? body.reason() : null,
                body != null ? body.reportId() : null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/stories/{storyId}")
    @RequiresStepUp
    public ResponseEntity<Void> deleteStory(@PathVariable UUID storyId,
                                            @RequestBody(required = false) ModerationActionRequest body) {
        adminContentService.deleteStory(storyId,
                body != null ? body.reason() : null,
                body != null ? body.reportId() : null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/highlights/{highlightId}/stories/{storyId}")
    public ResponseEntity<Void> unpinStory(@PathVariable UUID highlightId,
                                           @PathVariable UUID storyId,
                                           @RequestBody(required = false) ModerationActionRequest body) {
        adminContentService.unpinStoryFromHighlight(highlightId, storyId,
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    // ── platform keyword blocklist ──────────────────────────────────────

    @GetMapping("/blocklist")
    public ResponseEntity<List<PlatformKeyword>> blocklist() {
        return ResponseEntity.ok(platformKeywordService.list());
    }

    @PostMapping("/blocklist")
    @RequiresStepUp   // BLOCK severity gates publishing platform-wide
    public ResponseEntity<PlatformKeyword> addKeyword(@RequestBody KeywordRequest body) {
        PlatformKeyword.Severity severity = body.severity() != null
                ? body.severity() : PlatformKeyword.Severity.FLAG;
        return ResponseEntity.status(201)
                .body(platformKeywordService.add(body.keyword(), severity, body.note()));
    }

    @PatchMapping("/blocklist/{id}")
    public ResponseEntity<PlatformKeyword> updateKeyword(@PathVariable UUID id,
                                                         @RequestBody KeywordRequest body) {
        return ResponseEntity.ok(platformKeywordService.update(id, body.severity(), body.note()));
    }

    @DeleteMapping("/blocklist/{id}")
    public ResponseEntity<Void> removeKeyword(@PathVariable UUID id) {
        platformKeywordService.remove(id);
        return ResponseEntity.noContent().build();
    }

    /** Dry-run: see what the normalizer's contains-scan would match. */
    @PostMapping("/blocklist/test")
    public ResponseEntity<Map<String, Object>> testKeywords(@RequestBody KeywordTestRequest body) {
        List<String> matches = platformKeywordService.test(body.text());
        return ResponseEntity.ok(Map.of("matches", matches, "matched", !matches.isEmpty()));
    }
}
