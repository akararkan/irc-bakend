package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.cassandra.entity.MediaByPostEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraMediaService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Post media CRUD. Reads are public; every mutation requires the caller to be
 * the post's author (verified against {@code posts_by_id}) — media mutations
 * do not pass through the post-service author guard, so the check lives here.
 */
@RestController
@RequestMapping("/api/v1/posts/{postId}/media")
@RequiredArgsConstructor
public class CassandraMediaController {

    private final CassandraMediaService mediaService;

    public record AddMediaRequest(int sortOrder, String mediaType, String url,
                                  String thumbnailUrl, String s3Key,
                                  Integer durationSeconds, Long fileSizeBytes,
                                  String mimeType, String altText) {}

    @PostMapping
    public MediaByPostEntity addMedia(@PathVariable UUID postId,
                                      @RequestBody AddMediaRequest req,
                                      @AuthenticationPrincipal User user) {
        requireAuthenticatedAuthor(postId, user);
        return mediaService.addMedia(postId, req.sortOrder(), req.mediaType(), req.url(),
                req.thumbnailUrl(), req.s3Key(), req.durationSeconds(),
                req.fileSizeBytes(), req.mimeType(), req.altText());
    }

    @GetMapping
    public List<MediaByPostEntity> list(@PathVariable UUID postId) {
        return mediaService.listFor(postId);
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> remove(@PathVariable UUID postId,
                                       @PathVariable UUID mediaId,
                                       @RequestParam Integer sortOrder,
                                       @AuthenticationPrincipal User user) {
        requireAuthenticatedAuthor(postId, user);
        mediaService.removeMedia(postId, sortOrder, mediaId);
        return ResponseEntity.noContent().build();
    }

    /** Replace the entire ordered media list (drag-and-drop reorder). */
    @PutMapping
    public List<MediaByPostEntity> replaceAll(@PathVariable UUID postId,
                                              @RequestBody List<MediaByPostEntity> newOrder,
                                              @AuthenticationPrincipal User user) {
        requireAuthenticatedAuthor(postId, user);
        mediaService.replaceAll(postId, newOrder);
        return mediaService.listFor(postId);
    }

    private void requireAuthenticatedAuthor(UUID postId, User user) {
        if (user == null) throw new UnauthorizedException(PostMessages.AUTH_REQUIRED_MSG);
        mediaService.requireAuthor(postId, user.getId());
    }
}
