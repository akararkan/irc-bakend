package ak.dev.irc.app.common.tag.controller;

import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.entity.ContentByTagEntity;
import ak.dev.irc.app.common.tag.entity.TagCounterEntity;
import ak.dev.irc.app.common.tag.entity.TrendingTagEntity;
import ak.dev.irc.app.common.tag.repository.TrendingTagRepository;
import ak.dev.irc.app.common.tag.service.ContentTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public, unauthenticated tag surfaces: trending strip, a tag's content feed,
 * a single tag's usage count (per-scope or all-scopes breakdown), and a
 * prefix-autocomplete endpoint for chip inputs. All reads — no auth required.
 */
@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TrendingTagRepository trendingRepo;
    private final ContentTagService     contentTagService;

    /** Trending tag for the UI: name, usage count, and 0-based rank. */
    public record TrendingTag(String tag, long usageCount, int rank) {}

    /**
     * One row in a tag's content feed. {@code contentId} / {@code contentType}
     * mirror the field names on {@code GlobalSearchHit} so client code can
     * unify rendering of search results and tag rows.
     */
    public record TaggedContent(UUID contentId, String contentType, UUID authorId,
                                String titlePreview, Instant createdAt) {}

    /** One row in the tag autocomplete response. */
    public record TagSuggestion(String tag, long usageCount) {}

    /**
     * Trending tags (most-used), pre-ranked.
     *
     * @param scope {@code ALL} (default), {@code QUESTION}, {@code RESEARCH},
     *              {@code POST}, or {@code REEL}
     * @param limit max tags (default 20, max 100)
     */
    @GetMapping("/trending")
    public List<TrendingTag> trending(@RequestParam(defaultValue = "ALL") String scope,
                                      @RequestParam(defaultValue = "20") int limit) {
        return trendingRepo.topForScope(normalizeScope(scope), Math.min(Math.max(limit, 1), 100))
                .stream()
                .map(t -> new TrendingTag(t.getTag(),
                        t.getUsageCount() == null ? 0 : t.getUsageCount(),
                        t.getTagRank() == null ? 0 : t.getTagRank()))
                .toList();
    }

    /**
     * All content tagged {@code tag}, newest first, cursor-paged.
     *
     * <p>Cursor is an opaque base64 token returned alongside each page — never
     * decode it client-side. The legacy single-{@code createdAt} cursor format
     * still works for old callers (decoded into a {@code created_at}-only
     * range, no tiebreaker).</p>
     */
    @GetMapping("/{tag}/content")
    public Map<String, Object> content(@PathVariable String tag,
                                       @RequestParam(defaultValue = "20") int pageSize,
                                       @RequestParam(required = false) String cursor) {
        int clampedSize = Math.min(Math.max(pageSize, 1), 100);
        Cursor c = Cursor.decode(cursor);

        List<ContentByTagEntity> rows;
        if (c == null) {
            rows = contentTagService.contentForTag(tag, clampedSize);
        } else if (c.contentId == null) {
            // legacy ISO-instant-only cursor — accept for backward compatibility
            rows = contentTagService.contentForTagAfter(tag, c.createdAt, clampedSize);
        } else {
            rows = contentTagService.contentForTagAfter(tag, c.createdAt, c.contentId, clampedSize);
        }

        List<TaggedContent> items = rows.stream()
                .map(r -> new TaggedContent(r.getContentId(), r.getContentType(),
                        r.getAuthorId(), r.getTitlePreview(), r.getCreatedAt()))
                .toList();

        String nextCursor = items.isEmpty()
                ? null
                : Cursor.encode(items.get(items.size() - 1).createdAt(),
                                items.get(items.size() - 1).contentId());

        return Map.of(
                "tag",        tag.toLowerCase().strip(),
                "items",      items,
                "nextCursor", nextCursor == null ? "" : nextCursor,
                "pageSize",   clampedSize);
    }

    /**
     * Usage count for a single tag. Pass {@code scope=*} (or {@code scope=ALL_SCOPES})
     * to get every scope's count in one round-trip — useful for the tag-page
     * header chip (e.g. "312 research · 184 questions · 496 total").
     */
    @GetMapping("/{tag}/usage")
    public Map<String, Object> usage(@PathVariable String tag,
                                     @RequestParam(defaultValue = "ALL") String scope) {
        String normalisedTag = tag.toLowerCase().strip();
        if ("*".equals(scope) || "ALL_SCOPES".equalsIgnoreCase(scope)) {
            return Map.of(
                    "tag",    normalisedTag,
                    "scopes", contentTagService.usageBreakdown(normalisedTag));
        }
        String s = normalizeScope(scope);
        return Map.of("tag",        normalisedTag,
                      "scope",      s,
                      "usageCount", contentTagService.usageFor(s, normalisedTag));
    }

    /**
     * Prefix autocomplete for tag chip inputs. Returns up to {@code limit}
     * tags whose normalised name starts with {@code prefix}, ordered by name
     * (asc) within the scope partition.
     *
     * <p>This lets the frontend converge users on existing tags (e.g. typing
     * "rama" → "ramadan", "ramadan-2026") instead of accidentally minting new
     * variants. Backed by a clustering-key range scan on {@code tag_counters}
     * — no secondary index, no extra disk.</p>
     */
    @GetMapping("/search")
    public List<TagSuggestion> search(@RequestParam String prefix,
                                      @RequestParam(defaultValue = "ALL") String scope,
                                      @RequestParam(defaultValue = "10") int limit) {
        return contentTagService.prefixSearch(normalizeScope(scope), prefix, limit).stream()
                .map(t -> new TagSuggestion(t.getTag(),
                        t.getUsageCount() == null ? 0L : t.getUsageCount()))
                .toList();
    }

    private static String normalizeScope(String scope) {
        if (scope == null) return ContentType.SCOPE_ALL;
        String s = scope.trim().toUpperCase();
        return switch (s) {
            case "QUESTION", "RESEARCH", "POST", "REEL", "ALL" -> s;
            default -> ContentType.SCOPE_ALL;
        };
    }

    // ── Opaque cursor codec ──────────────────────────────────────────────────

    /**
     * Cursor wire-format: base64-url of {@code "<isoInstant>|<contentId>"}.
     * The legacy bare-{@code Instant} format (no pipe) is still accepted —
     * decoded into a cursor with a null {@code contentId} so old clients
     * keep functioning (without the tiebreaker).
     */
    private record Cursor(Instant createdAt, UUID contentId) {

        static String encode(Instant createdAt, UUID contentId) {
            String raw = createdAt.toString() + "|" + contentId.toString();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token) {
            if (token == null || token.isBlank()) return null;
            try {
                String decoded = new String(
                        Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                int sep = decoded.indexOf('|');
                if (sep < 0) {
                    // legacy bare-Instant cursor
                    return new Cursor(Instant.parse(decoded), null);
                }
                return new Cursor(
                        Instant.parse(decoded.substring(0, sep)),
                        UUID.fromString(decoded.substring(sep + 1)));
            } catch (Exception e) {
                // Bad token — be lenient, just start from the head.
                return null;
            }
        }
    }
}
