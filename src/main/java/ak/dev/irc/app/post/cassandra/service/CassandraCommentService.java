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
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReplyByCommentRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
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
 * Soft delete only: rows stay (so the thread shape doesn't collapse) but the
 * text is nulled and is_deleted=true. Counters DO decrement so "12 comments"
 * never includes ghosts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraCommentService {

    private final CommentByPostRepository   commentRepo;
    private final ReplyByCommentRepository  replyRepo;
    private final CommentLookupRepository   lookupRepo;
    private final PostCounterRepository     postCounterRepo;
    private final CommentCounterRepository  commentCounterRepo;
    private final CounterService            counterService;
    private final PostRealtimePublisher     realtimePublisher;
    private final PostByIdRepository        postRepo;
    private final UserRepository            userRepo;
    private final CassandraNotificationService notificationService;

    // ── Create top-level comment ─────────────────────────────────────────────

    public CommentByPostEntity createComment(UUID postId, UUID authorId,
                                             String text, String mediaUrl, String mediaType) {
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
        return row;
    }

    // ── Create reply (flat-at-1 enforcement) ────────────────────────────────

    public ReplyByCommentEntity replyTo(UUID targetCommentId, UUID authorId,
                                        String text, String mediaUrl) {
        CommentLookupEntity target = lookupRepo.findById(targetCommentId).orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("Comment not found: " + targetCommentId);
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

        broadcast(postId, replyId, truParentId, authorId, text, mediaUrl, null,
                  PostRealtimeEventType.REPLY_CREATED);
        notifyReply(postId, truParentId, replyId, authorId, text);
        return row;
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    public void editComment(UUID commentId, UUID authorId, String newText) {
        CommentLookupEntity meta = requireLookup(commentId);
        if (!meta.getAuthorId().equals(authorId)) {
            throw new SecurityException("Not the author");
        }
        if (Boolean.TRUE.equals(meta.getReply())) {
            replyRepo.editText(meta.getParentId(), meta.getCreatedAt(), commentId, newText);
        } else {
            commentRepo.editText(meta.getPostId(), meta.getCreatedAt(), commentId, newText);
        }
        broadcast(meta.getPostId(), commentId, meta.getParentId(), authorId, newText, null, null,
                  PostRealtimeEventType.COMMENT_EDITED);
    }

    // ── Soft delete ──────────────────────────────────────────────────────────

    public void deleteComment(UUID commentId, UUID authorId) {
        CommentLookupEntity meta = requireLookup(commentId);
        if (!meta.getAuthorId().equals(authorId)) {
            throw new SecurityException("Not the author");
        }

        if (Boolean.TRUE.equals(meta.getReply())) {
            replyRepo.softDelete(meta.getParentId(), meta.getCreatedAt(), commentId);
            counterService.decrementCommentReplies(meta.getParentId());
            counterService.decrementPostComments(meta.getPostId());
        } else {
            commentRepo.softDelete(meta.getPostId(), meta.getCreatedAt(), commentId);
            counterService.decrementPostComments(meta.getPostId());
            // Replies under a deleted top-level stay readable; only the body is gone.
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

    // ── Notification fan-out (async, deduped, blocked-aware) ───────────────

    private void notifyPostCommented(UUID postId, UUID commentId, UUID actorId, String text) {
        try {
            PostByIdEntity post = postRepo.findById(postId).orElse(null);
            if (post == null || post.getAuthorId() == null) return;
            String actor = actorLabel(actorId);
            String preview = preview(text);
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    post.getAuthorId(),
                    NotificationKind.POST_COMMENTED,
                    "New comment on your post",
                    actor + " commented: \"" + preview + "\"",
                    actorId,
                    "Post", postId,
                    "POST_COMMENTED:" + postId
            ));
        } catch (Exception e) {
            log.debug("[COMMENT] notify skipped: {}", e.getMessage());
        }
    }

    private void notifyReply(UUID postId, UUID parentCommentId, UUID replyId,
                             UUID actorId, String text) {
        try {
            CommentLookupEntity parent = lookupRepo.findById(parentCommentId).orElse(null);
            if (parent == null || parent.getAuthorId() == null) return;
            String actor = actorLabel(actorId);
            String preview = preview(text);
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    parent.getAuthorId(),
                    NotificationKind.POST_COMMENT_REPLIED,
                    "Someone replied to your comment",
                    actor + " replied: \"" + preview + "\"",
                    actorId,
                    "Comment", replyId,
                    "POST_COMMENT_REPLIED:" + parentCommentId
            ));
        } catch (Exception e) {
            log.debug("[COMMENT] reply notify skipped: {}", e.getMessage());
        }
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

    private void broadcast(UUID postId, UUID commentId, UUID parentId, UUID actorId,
                           String text, String mediaUrl, String mediaType,
                           PostRealtimeEventType type) {
        try {
            Long postCommentCount = postCounterRepo.findByPostId(postId)
                    .map(c -> c.getCommentCount()).orElse(null);
            Long parentReplyCount = parentId == null ? null
                    : commentCounterRepo.findByCommentId(parentId)
                            .map(c -> c.getReplyCount()).orElse(null);

            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .commentId(commentId)
                    .parentCommentId(parentId)
                    .actorId(actorId)
                    .textContent(text)
                    .mediaUrl(mediaUrl)
                    .mediaType(mediaType)
                    .postCommentCount(postCommentCount)
                    .commentReplyCount(parentReplyCount)
                    .build());
        } catch (Exception e) {
            log.debug("[COMMENT] realtime broadcast skipped: {}", e.getMessage());
        }
    }
}
