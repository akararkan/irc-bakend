package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.MediaByPostEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
    public MediaByPostEntity addMedia(@PathVariable UUID postId, @RequestBody AddMediaRequest req) {
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
                                       @RequestParam Integer sortOrder) {
        mediaService.removeMedia(postId, sortOrder, mediaId);
        return ResponseEntity.noContent().build();
    }

    /** Replace the entire ordered media list (drag-and-drop reorder). */
    @PutMapping
    public List<MediaByPostEntity> replaceAll(@PathVariable UUID postId,
                                              @RequestBody List<MediaByPostEntity> newOrder) {
        mediaService.replaceAll(postId, newOrder);
        return mediaService.listFor(postId);
    }
}
