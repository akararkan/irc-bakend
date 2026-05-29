package ak.dev.irc.app.common.tag.service;

import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.entity.ContentByTagEntity;
import ak.dev.irc.app.common.tag.entity.TagsByContentEntity;
import ak.dev.irc.app.common.tag.repository.ContentByTagRepository;
import ak.dev.irc.app.common.tag.repository.TagCounterRepository;
import ak.dev.irc.app.common.tag.repository.TagsByContentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import ak.dev.irc.app.common.tag.entity.TagCounterEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unified tag index shared by Q&A and Research (and open to Posts later).
 *
 * <p>Write fan-out per content item that carries {@code [hajj, ramadan]}:</p>
 * <ul>
 *   <li>{@code content_by_tag}   — one row per tag → the "all content tagged X" feed</li>
 *   <li>{@code tags_by_content}  — reverse index → clean delete/retag without re-parsing</li>
 *   <li>{@code tag_counters}     — +1 to scope {@code ALL} <em>and</em> the type scope → trending</li>
 * </ul>
 *
 * <p>Tags are normalized to lowercase, trimmed, leading {@code #} stripped,
 * de-duplicated, capped to {@value #MAX_TAGS} per item and {@value #MAX_TAG_LEN}
 * chars each. Every write is best-effort and isolated in try/catch so tagging
 * never fails the content create/update itself.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTagService {

    public static final int MAX_TAGS    = 30;
    public static final int MAX_TAG_LEN = 100;

    private final ContentByTagRepository  contentByTagRepo;
    private final TagsByContentRepository tagsByContentRepo;
    private final TagCounterRepository    tagCounterRepo;
    private final TagCounterService       tagCounterService;
    private final CqlOperations           cqlOperations;

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Index a content item's tags. Call exactly once per create. */
    public void tag(ContentType type, UUID contentId, UUID authorId,
                    String titlePreview, Instant createdAt, Collection<String> rawTags) {
        Set<String> tags = normalize(rawTags);
        if (tags.isEmpty()) return;
        String preview = preview(titlePreview);
        for (String t : tags) {
            try {
                contentByTagRepo.save(ContentByTagEntity.builder()
                        .tag(t).createdAt(createdAt).contentId(contentId)
                        .contentType(type.name()).authorId(authorId).titlePreview(preview)
                        .build());
                tagsByContentRepo.save(TagsByContentEntity.builder()
                        .contentId(contentId).tag(t)
                        .contentType(type.name()).createdAt(createdAt)
                        .build());
                tagCounterService.increment(ContentType.SCOPE_ALL, t);
                tagCounterService.increment(type.name(), t);
            } catch (Exception e) {
                log.warn("[TAG] index '{}' for {} {} failed: {}", t, type, contentId, e.getMessage());
            }
        }
    }

    /** Remove all of a content item's tags (decrementing counters + feed rows). */
    public void untag(UUID contentId) {
        List<TagsByContentEntity> existing;
        try {
            existing = tagsByContentRepo.findByContentId(contentId);
        } catch (Exception e) {
            log.warn("[TAG] untag lookup for {} failed: {}", contentId, e.getMessage());
            return;
        }
        for (TagsByContentEntity row : existing) {
            try {
                cqlOperations.execute(
                        "DELETE FROM content_by_tag WHERE tag = ? AND created_at = ? AND content_id = ?",
                        row.getTag(), row.getCreatedAt(), contentId);
                tagCounterService.decrement(ContentType.SCOPE_ALL, row.getTag());
                if (row.getContentType() != null) {
                    tagCounterService.decrement(row.getContentType(), row.getTag());
                }
            } catch (Exception e) {
                log.warn("[TAG] untag '{}' for {} failed: {}", row.getTag(), contentId, e.getMessage());
            }
        }
        try {
            cqlOperations.execute("DELETE FROM tags_by_content WHERE content_id = ?", contentId);
        } catch (Exception e) {
            log.warn("[TAG] reverse-index clear for {} failed: {}", contentId, e.getMessage());
        }
    }

    /**
     * Replace a content item's tags (edit path). Uses the original
     * {@code createdAt} so the rebuilt {@code content_by_tag} rows keep the
     * item's position in each tag feed.
     */
    public void retag(ContentType type, UUID contentId, UUID authorId,
                      String titlePreview, Instant createdAt, Collection<String> rawTags) {
        untag(contentId);
        tag(type, contentId, authorId, titlePreview, createdAt, rawTags);
    }

    /**
     * Refresh the {@code title_preview} column on every {@code content_by_tag}
     * row for this content without touching the tag set or the counters. Use
     * this from edit paths that change the title but not the tags — without
     * it, the denormalised preview goes stale until the next retag.
     */
    public void refreshTitlePreview(UUID contentId, String newTitlePreview) {
        if (contentId == null) return;
        String preview = preview(newTitlePreview);
        List<TagsByContentEntity> existing;
        try {
            existing = tagsByContentRepo.findByContentId(contentId);
        } catch (Exception e) {
            log.warn("[TAG] title-preview refresh lookup for {} failed: {}", contentId, e.getMessage());
            return;
        }
        for (TagsByContentEntity row : existing) {
            try {
                cqlOperations.execute(
                        "UPDATE content_by_tag SET title_preview = ? "
                                + "WHERE tag = ? AND created_at = ? AND content_id = ?",
                        preview, row.getTag(), row.getCreatedAt(), contentId);
            } catch (Exception e) {
                log.warn("[TAG] title-preview refresh '{}' for {} failed: {}",
                        row.getTag(), contentId, e.getMessage());
            }
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<ContentByTagEntity> contentForTag(String tag, int pageSize) {
        return contentByTagRepo.firstPage(norm(tag), pageSize);
    }

    public List<ContentByTagEntity> contentForTagAfter(String tag, Instant cursor, int pageSize) {
        return contentByTagRepo.nextPage(norm(tag), cursor, pageSize);
    }

    /**
     * Composite-cursor variant — disambiguates rows sharing a millisecond by
     * threading {@code content_id} through as a tiebreaker so paginating a busy
     * tag never skips or duplicates rows.
     */
    public List<ContentByTagEntity> contentForTagAfter(String tag, Instant cursorCreatedAt,
                                                       UUID cursorContentId, int pageSize) {
        if (cursorContentId == null) {
            return contentForTagAfter(tag, cursorCreatedAt, pageSize);
        }
        return contentByTagRepo.nextPageWithTiebreaker(
                norm(tag), cursorCreatedAt, cursorContentId, pageSize);
    }

    public long usageFor(String scope, String tag) {
        return tagCounterRepo.findOne(scope, norm(tag))
                .map(c -> c.getUsageCount() == null ? 0L : c.getUsageCount())
                .orElse(0L);
    }

    /**
     * Single-call breakdown across every scope a tag could appear in (ALL +
     * each {@link ContentType}). Empty scopes are still included with a count
     * of {@code 0} so the frontend renders zero-state chips consistently.
     */
    public Map<String, Long> usageBreakdown(String tag) {
        String normalised = norm(tag);
        Map<String, Long> out = new LinkedHashMap<>();
        out.put(ContentType.SCOPE_ALL, usageFor(ContentType.SCOPE_ALL, normalised));
        for (ContentType ct : ContentType.values()) {
            out.put(ct.name(), usageFor(ct.name(), normalised));
        }
        return out;
    }

    /**
     * Prefix-search the tag catalogue inside a scope (clustering-key range
     * scan — no secondary index needed). Returns matches ordered by tag name
     * ascending. {@code limit} is clamped to [1, 50] so a runaway client can't
     * pull the whole partition.
     */
    public List<TagCounterEntity> prefixSearch(String scope, String prefix, int limit) {
        String lo = norm(prefix);
        if (lo.isEmpty()) return List.of();
        // U+FFFF sorts after any normal codepoint, so [lo, lo+U+FFFF) covers
        // exactly the prefix — handles ASCII and non-ASCII (Arabic, etc.) alike.
        String hi = lo + "￿";
        int clamped = Math.min(Math.max(limit, 1), 50);
        return tagCounterRepo.prefixScan(scope == null ? ContentType.SCOPE_ALL : scope,
                lo, hi, clamped);
    }

    // ── Normalization ──────────────────────────────────────────────────────────

    /** Public so callers can persist the same normalized tag set they index. */
    public static Set<String> normalize(Collection<String> rawTags) {
        Set<String> out = new LinkedHashSet<>();
        if (rawTags == null) return out;
        for (String raw : rawTags) {
            String n = norm(raw);
            if (!n.isBlank()) out.add(n);
            if (out.size() >= MAX_TAGS) break;
        }
        return out;
    }

    private static String norm(String raw) {
        if (raw == null) return "";
        // Locale.ROOT pins case-folding to language-independent rules so a JVM
        // with a Turkish locale doesn't fold "I" → "ı" (and similar). Latin and
        // non-Latin scripts stay distinct — a tag in Arabic ("رمضان") is a
        // different bucket from its Latin transliteration ("ramadan") on
        // purpose; see SEARCH_API.md §7.2.
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) s = s.substring(1).trim();
        if (s.length() > MAX_TAG_LEN) s = s.substring(0, MAX_TAG_LEN);
        return s;
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }
}
