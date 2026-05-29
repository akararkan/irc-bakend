package ak.dev.irc.app.common.tag.controller;

import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.service.ContentTagService;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraHashtagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only one-shot maintenance endpoints for the unified tag subsystem.
 *
 * <p>The headliner here is {@link #backfillPosts()}: posts started fanning out
 * into {@code content_by_tag} as of the same commit that introduced this
 * controller. Any post created before that commit is missing from the
 * unified feed and shows up only via {@code /api/v1/hashtags/{tag}/posts}
 * (the legacy post-only feed). Run this once after deploying to migrate
 * historical post hashtags forward.</p>
 *
 * <p>Scale note: scans {@code posts_by_id} with a full token-range read.
 * Fine for moderate (≲ low millions) datasets; for a larger archive you'd
 * want a Spring Batch job or a Cassandra COPY-based migration instead.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
public class TagAdminController {

    private final PostByIdRepository  postByIdRepo;
    private final ContentTagService   contentTagService;

    /**
     * Idempotently re-indexes every post's hashtags into {@code content_by_tag}.
     * Safe to re-run: the underlying {@code tag(...)} write is just per-row
     * UPSERTs into the tag feed; the trending counter increments are NOT
     * idempotent though, so don't run this twice if you don't have to.
     *
     * <p>Returns a summary: posts scanned, posts that had ≥1 hashtag,
     * hashtag-rows written.</p>
     */
    @PostMapping("/backfill-posts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> backfillPosts() {
        long scanned = 0, withHashtags = 0, rowsWritten = 0;

        for (PostByIdEntity p : postByIdRepo.findAll()) {
            scanned++;
            Set<String> tags = CassandraHashtagService.extractHashtags(p.getTextContent());
            if (tags.isEmpty()) continue;
            withHashtags++;
            ContentType type = "REEL".equalsIgnoreCase(p.getPostType())
                    ? ContentType.REEL : ContentType.POST;
            String preview = preview(p.getTextContent());
            try {
                contentTagService.tag(type, p.getId(), p.getAuthorId(),
                        preview,
                        p.getCreatedAt() == null
                                ? java.time.Instant.now() : p.getCreatedAt(),
                        tags);
                rowsWritten += tags.size();
            } catch (Exception e) {
                log.warn("[BACKFILL] post {} failed: {}", p.getId(), e.getMessage());
            }
        }

        log.info("[BACKFILL] posts → content_by_tag: scanned={} withHashtags={} rowsWritten={}",
                scanned, withHashtags, rowsWritten);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postsScanned",       scanned);
        body.put("postsWithHashtags",  withHashtags);
        body.put("tagRowsWritten",     rowsWritten);
        body.put("startedAt",          java.time.Instant.now().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok(body);
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }
}
