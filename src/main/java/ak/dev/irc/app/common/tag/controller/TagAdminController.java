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
    private final ak.dev.irc.app.admin.support.AdminAuditor adminAuditor;
    private final ak.dev.irc.app.common.tag.repository.TrendingTagOverrideRepository overrideRepo;
    private final ak.dev.irc.app.common.tag.repository.ContentByTagRepository contentByTagRepo;
    private final ak.dev.irc.app.common.tag.service.TagCounterService tagCounterService;

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
    @ak.dev.irc.app.admin.support.RequiresStepUp   // high danger: trending bumps are NOT idempotent
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
        adminAuditor.record(ak.dev.irc.app.audit.enums.AuditOperation.UPDATE, "Tag", null,
                "ADMIN_TAG_BACKFILL",
                "scanned=" + scanned + ", withHashtags=" + withHashtags + ", rows=" + rowsWritten);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postsScanned",       scanned);
        body.put("postsWithHashtags",  withHashtags);
        body.put("tagRowsWritten",     rowsWritten);
        body.put("startedAt",          java.time.Instant.now().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok(body);
    }

    /**
     * Trending suppression (blueprint §3.4 hide/unhide): hide = an ALL-or-scope
     * BAN override, consulted by {@code TrendingTagJob} on the next rebuild.
     */
    @PostMapping("/{tag}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> hideTag(@PathVariable String tag,
                                        @RequestParam(defaultValue = "ALL") String scope) {
        String normScope = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeScope(scope);
        String normTag = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeTag(tag);
        boolean already = overrideRepo.findByTagNormalizedAndRevokedAtIsNull(normTag).stream()
                .anyMatch(o -> o.getType() == ak.dev.irc.app.common.tag.entity.TrendingTagOverride.OverrideType.BAN
                        && o.getScope().equalsIgnoreCase(normScope));
        if (!already) {
            overrideRepo.save(ak.dev.irc.app.common.tag.entity.TrendingTagOverride.builder()
                    .tagNormalized(normTag).scope(normScope)
                    .type(ak.dev.irc.app.common.tag.entity.TrendingTagOverride.OverrideType.BAN)
                    .reason("hidden via admin tags API")
                    .createdBy(ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null))
                    .build());
        }
        adminAuditor.record(ak.dev.irc.app.audit.enums.AuditOperation.UPDATE, "Tag", null,
                "ADMIN_TAG_HIDE", normTag + " scope=" + normScope);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{tag}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unhideTag(@PathVariable String tag,
                                          @RequestParam(defaultValue = "ALL") String scope) {
        String normScope = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeScope(scope);
        String normTag = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeTag(tag);
        overrideRepo.findByTagNormalizedAndRevokedAtIsNull(normTag).stream()
                .filter(o -> o.getType() == ak.dev.irc.app.common.tag.entity.TrendingTagOverride.OverrideType.BAN)
                .filter(o -> o.getScope().equalsIgnoreCase(normScope))
                .forEach(o -> {
                    o.setRevokedAt(java.time.LocalDateTime.now());
                    overrideRepo.save(o);
                });
        adminAuditor.record(ak.dev.irc.app.audit.enums.AuditOperation.UPDATE, "Tag", null,
                "ADMIN_TAG_UNHIDE", normTag + " scope=" + normScope);
        return ResponseEntity.noContent().build();
    }

    /**
     * Merge {@code from} into {@code to}: rewrites {@code content_by_tag}
     * rows and transfers the usage counters. Counter tables can't be moved
     * atomically — the documented drift window is one trending refresh.
     * Capped at 5000 rows per call; re-run for larger tags.
     */
    @PostMapping("/merge")
    @PreAuthorize("hasRole('ADMIN')")
    @ak.dev.irc.app.admin.support.RequiresStepUp
    public ResponseEntity<Map<String, Object>> mergeTags(@RequestParam String from,
                                                         @RequestParam String to) {
        String normFrom = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeTag(from);
        String normTo = ak.dev.irc.app.admin.trending.AdminTrendingController.normalizeTag(to);
        if (normFrom.equals(normTo)) {
            return ResponseEntity.badRequest().build();
        }
        int moved = 0;
        final int page = 200, cap = 5000;
        var batch = contentByTagRepo.firstPage(normFrom, page);
        java.util.Map<String, Long> movedByScope = new LinkedHashMap<>();
        while (!batch.isEmpty() && moved < cap) {
            for (var row : batch) {
                contentByTagRepo.save(ak.dev.irc.app.common.tag.entity.ContentByTagEntity.builder()
                        .tag(normTo).createdAt(row.getCreatedAt()).contentId(row.getContentId())
                        .contentType(row.getContentType()).authorId(row.getAuthorId())
                        .titlePreview(row.getTitlePreview())
                        .build());
                contentByTagRepo.delete(row);
                movedByScope.merge(row.getContentType() == null ? "POST" : row.getContentType(),
                        1L, Long::sum);
                moved++;
            }
            if (batch.size() < page || moved >= cap) break;
            batch = contentByTagRepo.firstPage(normFrom, page);
        }
        // Counter transfer: per-scope delta + the ALL rollup.
        long total = 0;
        for (var e : movedByScope.entrySet()) {
            tagCounterService.addDelta(e.getKey(), normFrom, -e.getValue());
            tagCounterService.addDelta(e.getKey(), normTo, e.getValue());
            total += e.getValue();
        }
        if (total > 0) {
            tagCounterService.addDelta(ContentType.SCOPE_ALL, normFrom, -total);
            tagCounterService.addDelta(ContentType.SCOPE_ALL, normTo, total);
        }
        adminAuditor.record(ak.dev.irc.app.audit.enums.AuditOperation.UPDATE, "Tag", null,
                "ADMIN_TAG_MERGE", normFrom + " → " + normTo + " (" + moved + " rows)");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", normFrom);
        body.put("to", normTo);
        body.put("rowsMoved", moved);
        body.put("truncated", moved >= cap);
        return ResponseEntity.ok(body);
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }
}
