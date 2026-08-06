package ak.dev.irc.app.admin.content;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.repository.CommentByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.HighlightByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReelsByDayRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryInHighlightRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraCommentService;
import ak.dev.irc.app.post.cassandra.service.CassandraHashtagService;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryService;
import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.service.ContentTagService;
import ak.dev.irc.app.post.search.service.PostSearchService;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin content moderation over the social plane (blueprint §3.2,
 * content-moderation.md): the first writer of {@code PostStatus.REMOVED},
 * admin comment/story deletion (snapshot-first — both are irreversible), and
 * highlight-pin removal. Search-side enforcement pre-exists
 * ({@code GlobalSearchService.DEAD_STATUSES}); feed-side enforcement is the
 * status gate added to {@code PostHydrator}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminContentService {

    private final PostByIdRepository postByIdRepo;
    private final PostByAuthorRepository postByAuthorRepo;
    private final PostCounterRepository postCounterRepo;
    private final ReelsByDayRepository reelsByDayRepo;
    private final PostSearchService postSearchService;
    private final ContentTagService contentTagService;
    private final CassandraCommentService commentService;
    private final CommentLookupRepository commentLookupRepo;
    private final CommentByPostRepository commentByPostRepo;
    private final CassandraStoryService storyService;
    private final StoryLookupRepository storyLookupRepo;
    private final HighlightByAuthorRepository highlightByAuthorRepo;
    private final StoryInHighlightRepository storyInHighlightRepo;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;
    private final NotificationService notificationService;

    public record AdminPostRow(UUID id, UUID authorId, String postType, String status,
                               String visibility, String textContent, List<String> mediaUrls,
                               Instant createdAt, Instant updatedAt,
                               Map<String, Long> counters) {
    }

    // ── browse / detail ─────────────────────────────────────────────────

    /**
     * Author-scoped keyset browse (Cassandra partition scope — mirrors the
     * audit browser's required-anchor rule; there is no global post index).
     */
    public List<AdminPostRow> postsByAuthor(UUID authorId, String status, Instant cursor, int pageSize) {
        if (authorId == null) {
            throw new BadRequestException(
                    "authorId is required — posts are partitioned by author. "
                            + "Use search or reports to locate a post id directly.",
                    "AUTHOR_SCOPE_REQUIRED");
        }
        var rows = cursor == null
                ? postByAuthorRepo.firstPage(authorId, pageSize)
                : postByAuthorRepo.nextPage(authorId, cursor, pageSize);
        List<AdminPostRow> out = new ArrayList<>();
        for (var row : rows) {
            PostByIdEntity live = postByIdRepo.findById(row.getPostId()).orElse(null);
            if (live == null) continue;
            if (status != null && !status.isBlank()
                    && !status.trim().equalsIgnoreCase(live.getStatus())) {
                continue;
            }
            out.add(toRow(live));
        }
        return out;
    }

    public AdminPostRow post(UUID postId) {
        return toRow(requirePost(postId));
    }

    // ── post / reel takedown & restore ──────────────────────────────────

    public void removePost(UUID postId, String reason, UUID reportId) {
        PostByIdEntity post = requirePost(postId);
        if ("REMOVED".equalsIgnoreCase(post.getStatus())) {
            return; // idempotent
        }
        post.setStatus("REMOVED");
        post.setUpdatedAt(Instant.now());
        postByIdRepo.save(post);

        // Search + trending suppression; counters/reactions are retained so a
        // restore keeps engagement intact (design rule).
        postSearchService.deleteAsync(postId);
        quietly("untag", () -> contentTagService.untag(postId));
        if (isReel(post)) {
            quietly("reels_by_day", () -> reelsByDayRepo.deleteRow(
                    dayBucket(post.getCreatedAt()), post.getCreatedAt(), postId));
        }

        moderationRecorder.decision("POST", postId.toString(), "ADMIN_POST_REMOVE",
                reason, reportId, "type=" + post.getPostType());
        adminAuditor.record(AuditOperation.UPDATE, "Post", postId, "ADMIN_POST_REMOVE", reason);
        notifyAuthor(post.getAuthorId(), "Your post was removed",
                "A post of yours was removed for a policy violation"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + ".");
    }

    public void restorePost(UUID postId) {
        PostByIdEntity post = requirePost(postId);
        if (!"REMOVED".equalsIgnoreCase(post.getStatus())) {
            throw new BadRequestException("Only REMOVED posts can be restored.", "POST_NOT_REMOVED");
        }
        post.setStatus("PUBLISHED");
        post.setUpdatedAt(Instant.now());
        postByIdRepo.save(post);

        postSearchService.indexAsync(post);
        Set<String> tags = CassandraHashtagService.extractHashtags(post.getTextContent());
        if (!tags.isEmpty()) {
            ContentType type = isReel(post) ? ContentType.REEL : ContentType.POST;
            quietly("retag", () -> contentTagService.tag(type, post.getId(), post.getAuthorId(),
                    preview(post.getTextContent()),
                    post.getCreatedAt() == null ? Instant.now() : post.getCreatedAt(), tags));
        }
        if (isReel(post)) {
            quietly("reels_by_day restore", () -> reelsByDayRepo.save(
                    ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity.builder()
                            .dayBucket(dayBucket(post.getCreatedAt()))
                            .createdAt(post.getCreatedAt())
                            .postId(post.getId())
                            .authorId(post.getAuthorId())
                            .textPreview(preview(post.getTextContent()))
                            .mediaUrl(post.getMediaUrls() == null || post.getMediaUrls().isEmpty()
                                    ? null : post.getMediaUrls().get(0))
                            .build()));
        }

        moderationRecorder.decision("POST", postId.toString(), "ADMIN_POST_RESTORE", null, null, null);
        adminAuditor.record(AuditOperation.UPDATE, "Post", postId, "ADMIN_POST_RESTORE");
        notifyAuthor(post.getAuthorId(), "Your post was restored",
                "A previously removed post of yours has been restored.");
    }

    // ── comment / story deletion (irreversible — snapshot first) ────────

    public void deleteComment(UUID commentId, String reason, UUID reportId) {
        CommentLookupEntity meta = commentLookupRepo.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", "id", commentId));

        // Evidence BEFORE the hard delete — the reply subtree dies with it.
        String payload = null;
        try {
            CommentByPostEntity row = Boolean.TRUE.equals(meta.getReply())
                    ? null
                    : commentByPostRepo.findRow(meta.getPostId(), meta.getCreatedAt(), commentId)
                            .orElse(null);
            payload = "{\"postId\":\"" + meta.getPostId() + "\",\"authorId\":\"" + meta.getAuthorId()
                    + "\",\"text\":" + jsonString(row != null ? row.getTextContent() : null)
                    + ",\"reply\":" + Boolean.TRUE.equals(meta.getReply()) + "}";
        } catch (Exception e) {
            log.warn("[ADMIN-CONTENT] comment snapshot failed: {}", e.getMessage());
        }
        moderationRecorder.snapshot("COMMENT", commentId.toString(), meta.getAuthorId(), payload, null);

        // Reuse the exact hard-delete path by acting as the comment's author —
        // identical tombstone/counter mechanics, no parallel delete logic.
        commentService.deleteComment(commentId, meta.getAuthorId());

        moderationRecorder.decision("COMMENT", commentId.toString(), "ADMIN_COMMENT_DELETE",
                reason, reportId, null);
        adminAuditor.record(AuditOperation.DELETE, "PostComment", commentId,
                "ADMIN_COMMENT_DELETE", reason);
        notifyAuthor(meta.getAuthorId(), "Your comment was removed",
                "A comment of yours was removed for a policy violation"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + ".");
    }

    public void deleteStory(UUID storyId, String reason, UUID reportId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        UUID authorId = meta.getAuthorId();

        moderationRecorder.snapshot("STORY", storyId.toString(), authorId,
                "{\"authorId\":\"" + authorId + "\",\"visibility\":"
                        + jsonString(meta.getVisibility()) + ",\"expiresAt\":"
                        + jsonString(String.valueOf(meta.getExpiresAt())) + "}", null);

        // Author-path delete (story rows + poll rows + tray fan-out)…
        storyService.deleteStory(storyId, authorId);

        // …plus every highlight pin — expired stories live on in highlights.
        int pinsRemoved = 0;
        try {
            for (var highlight : highlightByAuthorRepo.listFor(authorId)) {
                for (var pinned : storyInHighlightRepo.listFor(highlight.getHighlightId())) {
                    if (storyId.equals(pinned.getStoryId())) {
                        storyInHighlightRepo.delete(highlight.getHighlightId(),
                                pinned.getCreatedAt(), storyId);
                        pinsRemoved++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ADMIN-CONTENT] highlight-pin sweep failed for story {}: {}",
                    storyId, e.getMessage());
        }

        moderationRecorder.decision("STORY", storyId.toString(), "ADMIN_STORY_DELETE",
                reason, reportId, "highlightPinsRemoved=" + pinsRemoved);
        adminAuditor.record(AuditOperation.DELETE, "Story", storyId, "ADMIN_STORY_DELETE", reason);
        notifyAuthor(authorId, "Your story was removed",
                "A story of yours was removed for a policy violation"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + ".");
    }

    public void unpinStoryFromHighlight(UUID highlightId, UUID storyId, String reason) {
        boolean removed = false;
        for (var pinned : storyInHighlightRepo.listFor(highlightId)) {
            if (storyId.equals(pinned.getStoryId())) {
                storyInHighlightRepo.delete(highlightId, pinned.getCreatedAt(), storyId);
                removed = true;
            }
        }
        if (!removed) {
            throw new ResourceNotFoundException("HighlightItem", "storyId", storyId);
        }
        moderationRecorder.decision("HIGHLIGHT_ITEM", highlightId + ":" + storyId,
                "ADMIN_HIGHLIGHT_ITEM_REMOVE", reason, null, null);
        adminAuditor.record(AuditOperation.DELETE, "Highlight", highlightId,
                "ADMIN_HIGHLIGHT_ITEM_REMOVE", "story=" + storyId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private PostByIdEntity requirePost(UUID postId) {
        return postByIdRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
    }

    private AdminPostRow toRow(PostByIdEntity p) {
        Map<String, Long> counters = postCounterRepo.findByPostId(p.getId())
                .map(c -> Map.of(
                        "reactions", zero(c.getReactionCount()),
                        "comments", zero(c.getCommentCount()),
                        "shares", zero(c.getShareCount()),
                        "views", zero(c.getViewCount()),
                        "saves", zero(c.getSaveCount())))
                .orElse(Map.of());
        return new AdminPostRow(p.getId(), p.getAuthorId(), p.getPostType(), p.getStatus(),
                p.getVisibility(), p.getTextContent(), p.getMediaUrls(),
                p.getCreatedAt(), p.getUpdatedAt(), counters);
    }

    private void notifyAuthor(UUID authorId, String title, String body) {
        try {
            notificationService.sendSystemNotification(authorId, title, body);
        } catch (Exception e) {
            log.debug("[ADMIN-CONTENT] author notification skipped: {}", e.getMessage());
        }
    }

    private static boolean isReel(PostByIdEntity p) {
        return "REEL".equalsIgnoreCase(p.getPostType());
    }

    private static String dayBucket(Instant createdAt) {
        Instant at = createdAt == null ? Instant.now() : createdAt;
        return LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() <= 280 ? text : text.substring(0, 280);
    }

    private static long zero(Long v) {
        return v == null ? 0L : v;
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[ADMIN-CONTENT] {} failed: {}", what, e.getMessage());
        }
    }
}
