package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReplyByCommentEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.CommentByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.ReplyByCommentRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.common.cache.DedupGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cassandra-backed comments + replies.
 *
 * Depth-1 rule (project convention): a "reply to a reply" lands as a SIBLING
 * of the existing reply, NOT a deeper child. Implemented by resolving the
 * target's true parent through {@code comment_lookup} before writing the row.
 *
 * Write fan-out per top-level comment:
 *   • comments_by_post     ← chronological per-post thread
 *   • comment_lookup       ← point-read metadata (post_id, created_at, ...)
 *   • post_counters++      ← post's comment_count
 *   (no counter row needed for the comment itself yet — created on first react)
 *
 * Write fan-out per reply:
 *   • replies_by_comment   ← thread under the top-level comment
 *   • comment_lookup       ← parent_id pointing at the resolved top-level
 *   • comment_counters++   ← parent's reply_count
 *   • post_counters++      ← yes, the post's comment_count too (replies count)
 *
 * Edit / delete need the original (post_id|parent_id, created_at) — we fetch
 * that from comment_lookup, which is keyed by comment_id alone.
 *
 * Hard delete: the comment / reply row is physically removed from
 * {@code comments_by_post} (or {@code replies_by_comment}), plus its
 * lookup-table row. Deleting a top-level comment also range-deletes its
 * entire reply partition in one tombstone — keeps the parent partition
 * scan-cheap and prevents orphan replies. Counters decrement to match.
 * The previous "soft delete" pattern (UPDATE is_deleted=true) left rows
 * in place forever and bloated long threads; that's gone now.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraCommentService {

    private final CommentByPostRepository   commentRepo;
    private final ReplyByCommentRepository  replyRepo;
    private final CommentLookupRepository   lookupRepo;
    private final CommentCounterRepository  commentCounterRepo;
    private final CounterService            counterService;
    private final PostRealtimePublisher     realtimePublisher;
    private final PostByIdRepository        postRepo;
    private final UserRepository            userRepo;
    private final CassandraNotificationService notificationService;
    private final DedupGuard                   dedupGuard;
    private final ak.dev.irc.app.common.service.MentionService mentionService;
    private final AuthorAffinityService        affinityService;

    // ── Create top-level comment ─────────────────────────────────────────────

    public CommentByPostEntity createComment(UUID postId, UUID authorId,
                                             String text, String mediaUrl, String mediaType) {
        if (dedupGuard.isDuplicate("post-comment", postId, authorId, text)) {
            log.debug("[COMMENT] dedup hit — author={} post={} skipping duplicate", authorId, postId);
            CommentByPostEntity existing = mostRecentMatchingComment(postId, authorId, text);
            if (existing != null) return existing;
        }

        UUID    commentId = UUID.randomUUID();
        Instant now       = Instant.now();

        CommentByPostEntity row = CommentByPostEntity.builder()
                .postId(postId).createdAt(now).commentId(commentId)
                .authorId(authorId).textContent(text)
                .mediaUrl(mediaUrl).mediaType(mediaType)
                .deleted(false).edited(false)
                .build();
        commentRepo.save(row);

        lookupRepo.save(CommentLookupEntity.builder()
                .commentId(commentId).postId(postId).parentId(null)
                .authorId(authorId).createdAt(now).reply(false)
                .build());

        counterService.incrementPostComments(postId);
        broadcast(postId, commentId, null, authorId, text, mediaUrl, mediaType,
                  PostRealtimeEventType.COMMENT_CREATED);
        notifyPostCommented(postId, commentId, authorId, text);
        scanMentions(null, text, commentId, postId, authorId);
        affinityService.recordForPost(authorId, postId, AuthorAffinityService.WEIGHT_COMMENT);
        return row;
    }

    // ── Create reply (flat-at-1 enforcement) ────────────────────────────────

    public ReplyByCommentEntity replyTo(UUID targetCommentId, UUID authorId,
                                        String text, String mediaUrl) {
        CommentLookupEntity target = lookupRepo.findById(targetCommentId).orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("Comment not found: " + targetCommentId);
        }

        if (dedupGuard.isDuplicate("post-reply", targetCommentId, authorId, text)) {
            log.debug("[REPLY] dedup hit — author={} target={} skipping duplicate", authorId, targetCommentId);
            ReplyByCommentEntity existing = mostRecentMatchingReply(
                    Boolean.TRUE.equals(target.getReply()) ? target.getParentId() : targetCommentId,
                    authorId, text);
            if (existing != null) return existing;
        }

        // Resolve the *true* parent. If target itself is a reply, attach to
        // its parent (so we stay at depth 1). If it's a top-level comment,
        // attach to it directly.
        UUID truParentId = Boolean.TRUE.equals(target.getReply())
                ? target.getParentId()
                : targetCommentId;

        UUID    postId   = target.getPostId();
        UUID    replyId  = UUID.randomUUID();
        Instant now      = Instant.now();

        ReplyByCommentEntity row = ReplyByCommentEntity.builder()
                .parentId(truParentId).createdAt(now).replyId(replyId)
                .authorId(authorId).postId(postId).textContent(text)
                .mediaUrl(mediaUrl).deleted(false).edited(false)
                .build();
        replyRepo.save(row);

        lookupRepo.save(CommentLookupEntity.builder()
                .commentId(replyId).postId(postId).parentId(truParentId)
                .authorId(authorId).createdAt(now).reply(true)
                .build());

        counterService.incrementCommentReplies(truParentId);
        counterService.incrementPostComments(postId);
        affinityService.recordForPost(authorId, postId, AuthorAffinityService.WEIGHT_COMMENT);

        broadcast(postId, replyId, truParentId, authorId, text, mediaUrl, null,
                  PostRealtimeEventType.REPLY_CREATED);
        notifyReply(postId, truParentId, replyId, authorId, text);
        scanMentions(null, text, replyId, postId, authorId);
        return row;
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    public void editComment(UUID commentId, UUID authorId, String newText) {
        CommentLookupEntity meta = requireLookup(commentId);
        if (!meta.getAuthorId().equals(authorId)) {
            throw new SecurityException("Not the author");
        }
        // Pre-edit text so the mention scan only pings NEWLY added handles.
        String oldText;
        if (Boolean.TRUE.equals(meta.getReply())) {
            oldText = replyRepo.findRow(meta.getParentId(), meta.getCreatedAt(), commentId)
                    .map(ReplyByCommentEntity::getTextContent).orElse(null);
            replyRepo.editText(meta.getParentId(), meta.getCreatedAt(), commentId, newText);
        } else {
            oldText = commentRepo.findRow(meta.getPostId(), meta.getCreatedAt(), commentId)
                    .map(CommentByPostEntity::getTextContent).orElse(null);
            commentRepo.editText(meta.getPostId(), meta.getCreatedAt(), commentId, newText);
        }
        broadcast(meta.getPostId(), commentId, meta.getParentId(), authorId, newText, null, null,
                  PostRealtimeEventType.COMMENT_EDITED);
        scanMentions(oldText, newText, commentId, meta.getPostId(), authorId);
    }

    /**
     * @-mention scan for comment/reply text. Create passes {@code oldText=null}
     * (full scan); edit passes the pre-edit body so only newly added handles
     * get pinged. {@code @followers} is never honoured on comments. The
     * author-username read is skipped entirely when the text has no {@code @}.
     */
    private void scanMentions(String oldText, String newText, UUID commentId, UUID postId, UUID authorId) {
        try {
            if (newText == null || newText.indexOf('@') < 0) return;
            String username = userRepo.findById(authorId)
                    .map(ak.dev.irc.app.user.entity.User::getUsername).orElse(null);
            if (oldText == null) {
                mentionService.scanAndPublish(newText,
                        ak.dev.irc.app.rabbitmq.event.user.MentionSource.POST_COMMENT,
                        commentId, postId, authorId, username,
                        /* allowFollowersToken */ false);
            } else {
                mentionService.scanAndPublishDelta(oldText, newText,
                        ak.dev.irc.app.rabbitmq.event.user.MentionSource.POST_COMMENT,
                        commentId, postId, authorId, username);
            }
        } catch (Exception e) {
            log.warn("[COMMENT] mention scan failed for {}: {}", commentId, e.getMessage());
        }
    }

    // ── Hard delete ──────────────────────────────────────────────────────────

    /**
     * Physically remove a comment or reply. Replaces the old soft-delete
     * pattern (UPDATE is_deleted=true) which left rows in the partition
     * forever and made long threads slower to read with every delete.
     *
     * <p>Cascade on top-level: range-deletes the reply partition in a single
     * tombstone and decrements the post's comment counter by 1 + replyCount
     * so the visible total stays accurate. Lookup rows for the orphan replies
     * are not chased per-row (scatter-deletes across many partitions); a
     * background sweeper can reap them, and they're harmless until then since
     * the data rows they point to are gone.</p>
     */
    public void deleteComment(UUID commentId, UUID authorId) {
        CommentLookupEntity meta = requireLookup(commentId);
        if (!meta.getAuthorId().equals(authorId)) {
            throw new SecurityException("Not the author");
        }

        if (Boolean.TRUE.equals(meta.getReply())) {
            replyRepo.hardDelete(meta.getParentId(), meta.getCreatedAt(), commentId);
            lookupRepo.deleteById(commentId);
            counterService.decrementCommentReplies(meta.getParentId());
            counterService.decrementPostComments(meta.getPostId());
        } else {
            long replyCount = commentCounterRepo.findByCommentId(commentId)
                    .map(c -> c.getReplyCount() == null ? 0L : c.getReplyCount())
                    .orElse(0L);
            commentRepo.hardDelete(meta.getPostId(), meta.getCreatedAt(), commentId);
            lookupRepo.deleteById(commentId);
            replyRepo.deleteAllUnder(commentId);
            counterService.decrementPostComments(meta.getPostId());
            counterService.decrementPostCommentsBy(meta.getPostId(), replyCount);
        }

        broadcast(meta.getPostId(), commentId, meta.getParentId(), authorId, null, null, null,
                  PostRealtimeEventType.COMMENT_DELETED);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<CommentByPostEntity> commentsForPost(UUID postId, int pageSize) {
        return commentRepo.firstPage(postId, pageSize);
    }

    public List<CommentByPostEntity> commentsForPostAfter(UUID postId, Instant cursor, int pageSize) {
        return commentRepo.nextPage(postId, cursor, pageSize);
    }

    public List<ReplyByCommentEntity> repliesFor(UUID parentCommentId, int pageSize) {
        return replyRepo.firstPage(parentCommentId, pageSize);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CommentLookupEntity requireLookup(UUID commentId) {
        return lookupRepo.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
    }

    // Both dedup helpers scan the NEWEST rows — the duplicate we're trying to
    // return was created moments ago, so it is at the tail of the partition.
    // firstPage() would return the ten OLDEST rows and never find it on any
    // thread with more than ten comments, falling through to create a real
    // duplicate and double-bump the counter.

    private CommentByPostEntity mostRecentMatchingComment(UUID postId, UUID authorId, String text) {
        return commentRepo.newestPage(postId, 10).stream()
                .filter(r -> authorId.equals(r.getAuthorId()))
                .filter(r -> text == null ? r.getTextContent() == null : text.equals(r.getTextContent()))
                .findFirst()
                .orElse(null);
    }

    private ReplyByCommentEntity mostRecentMatchingReply(UUID parentId, UUID authorId, String text) {
        return replyRepo.newestPage(parentId, 10).stream()
                .filter(r -> authorId.equals(r.getAuthorId()))
                .filter(r -> text == null ? r.getTextContent() == null : text.equals(r.getTextContent()))
                .findFirst()
                .orElse(null);
    }

    // ── Notification fan-out (async; enrichment runs on the executor) ─────
    // The post / user / comment-lookup reads needed to populate the
    // notification body happen INSIDE the deliverAsync(Supplier) lambda so
    // they execute on the background thread, not the request thread.

    private void notifyPostCommented(UUID postId, UUID commentId, UUID actorId, String text) {
        String previewSnap = preview(text);
        notificationService.deliverAsync(() -> {
            PostByIdEntity post = postRepo.findById(postId).orElse(null);
            if (post == null || post.getAuthorId() == null) return null;
            String actor = actorLabel(actorId);
            return new CassandraNotificationService.DeliverRequest(
                    post.getAuthorId(),
                    NotificationKind.POST_COMMENTED,
                    "New comment on your post",
                    actor + " commented: \"" + previewSnap + "\"",
                    actorId,
                    "Post", postId,
                    "POST_COMMENTED:" + postId
            );
        });
    }

    private void notifyReply(UUID postId, UUID parentCommentId, UUID replyId,
                             UUID actorId, String text) {
        String previewSnap = preview(text);
        notificationService.deliverAsync(() -> {
            CommentLookupEntity parent = lookupRepo.findById(parentCommentId).orElse(null);
            if (parent == null || parent.getAuthorId() == null) return null;
            String actor = actorLabel(actorId);
            return new CassandraNotificationService.DeliverRequest(
                    parent.getAuthorId(),
                    NotificationKind.POST_COMMENT_REPLIED,
                    "Someone replied to your comment",
                    actor + " replied: \"" + previewSnap + "\"",
                    actorId,
                    "Comment", replyId,
                    "POST_COMMENT_REPLIED:" + parentCommentId
            );
        });
    }

    private String actorLabel(UUID actorId) {
        if (actorId == null) return "Someone";
        return userRepo.findById(actorId)
                .map(User::getUsername)
                .map(u -> "@" + u)
                .orElse("Someone");
    }

    private static String preview(String text) {
        if (text == null) return "";
        String trimmed = text.length() > 80 ? text.substring(0, 80) + "…" : text;
        return trimmed.replace("\n", " ");
    }

    // Counter columns are eventually consistent — re-reading right after an
    // increment can return a stale value, so we don't bundle fresh counts in
    // realtime events. Clients apply the +1 / -1 delta locally from the event
    // type and reconcile via REST on the next fetch.
    private void broadcast(UUID postId, UUID commentId, UUID parentId, UUID actorId,
                           String text, String mediaUrl, String mediaType,
                           PostRealtimeEventType type) {
        try {
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .commentId(commentId)
                    .parentCommentId(parentId)
                    .actorId(actorId)
                    .textContent(text)
                    .mediaUrl(mediaUrl)
                    .mediaType(mediaType)
                    .build());
        } catch (Exception e) {
            log.debug("[COMMENT] realtime broadcast skipped: {}", e.getMessage());
        }
    }
}
